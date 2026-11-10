// Tote Relay — dumb ciphertext mailbox.
package main

import (
	"crypto/sha256"
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	_ "modernc.org/sqlite"
)

const (
	defaultPort = "8080"
	maxBlobSize = 4 << 20
	powTarget   = 20
)

var db *sql.DB
var limiter = &RateLimiter{}

type RateLimiter struct {
	mu      sync.Mutex
	buckets map[string]*bucket
}

type bucket struct {
	tokens    float64
	lastCheck time.Time
}

func (r *RateLimiter) Allow(ip string, rate float64, burst int) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.buckets == nil {
		r.buckets = make(map[string]*bucket)
	}
	b, ok := r.buckets[ip]
	if !ok {
		b = &bucket{tokens: float64(burst), lastCheck: time.Now()}
		r.buckets[ip] = b
	}
	now := time.Now()
	b.tokens += now.Sub(b.lastCheck).Seconds() * rate
	if b.tokens > float64(burst) {
		b.tokens = float64(burst)
	}
	b.lastCheck = now
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

func verifyPoW(inboxID string, nonce []byte) bool {
	h := sha256.Sum256(append([]byte(inboxID), nonce...))
	zeroBits := 0
	for _, b := range h {
		if b == 0 { zeroBits += 8; continue }
		for bit := 7; bit >= 0; bit-- {
			if b&(1<<bit) != 0 { return zeroBits >= powTarget }
			zeroBits++
		}
	}
	return zeroBits >= powTarget
}

func healthz(w http.ResponseWriter, r *http.Request) {
	json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
}

func postEnvelope(w http.ResponseWriter, r *http.Request) {
	ip := getClientIP(r)
	if !limiter.Allow(ip, 1.0, 10) {
		http.Error(w, "rate limited", http.StatusTooManyRequests); return
	}
	var body struct {
		InboxID    string `json:"inbox_id"`
		Ciphertext string `json:"ciphertext"`
		TTL        int    `json:"ttl"`
		PoWNonce   string `json:"pow_nonce"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		http.Error(w, "bad JSON", http.StatusBadRequest); return
	}
	if body.InboxID == "" || body.Ciphertext == "" {
		http.Error(w, "missing inbox_id or ciphertext", http.StatusBadRequest); return
	}
	ct, err := hexDecode(body.Ciphertext)
	if err != nil { http.Error(w, "bad ciphertext hex", http.StatusBadRequest); return }
	if len(ct) > maxBlobSize {
		http.Error(w, "payload too large", http.StatusRequestEntityTooLarge); return
	}
	if !verifyPoW(body.InboxID, []byte(body.PoWNonce)) {
		http.Error(w, "PoW failed", http.StatusForbidden); return
	}
	if body.TTL <= 0 || body.TTL > 604800 { body.TTL = 604800 }
	id := fmt.Sprintf("%x", sha256.Sum256([]byte(time.Now().String()+body.InboxID)))[:32]
	cls := "small"; if len(ct) > 4096 { cls = "medium" }; if len(ct) > 65536 { cls = "large" }
	_, err = db.Exec("INSERT INTO envelopes (id,inbox_id,ciphertext,created_at,ttl,size_class) VALUES (?,?,?,?,?,?)",
		id, body.InboxID, ct, time.Now().Unix(), body.TTL, cls)
	if err != nil { http.Error(w, "storage error", http.StatusInternalServerError); return }
	w.Header().Set("Content-Type", "application/json"); w.WriteHeader(201)
	json.NewEncoder(w).Encode(map[string]string{"id": id})
}

func getEnvelope(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	var ct []byte; var ca int64; var ttl int
	err := db.QueryRow("SELECT ciphertext,created_at,ttl FROM envelopes WHERE id=?", id).Scan(&ct, &ca, &ttl)
	if err != nil { http.Error(w, "not found", 404); return }
	if time.Now().Unix()-ca > int64(ttl) { db.Exec("DELETE FROM envelopes WHERE id=?", id); http.Error(w, "expired", 410); return }
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{"id": id, "ciphertext": fmt.Sprintf("%x", ct), "created_at": ca, "ttl": ttl})
}

func deleteEnvelope(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	res, err := db.Exec("DELETE FROM envelopes WHERE id=?", id)
	if err != nil { http.Error(w, "error", 500); return }
	if n, _ := res.RowsAffected(); n == 0 { http.Error(w, "not found", 404); return }
	w.WriteHeader(200)
}

func ttlSweeper() {
	t := time.NewTicker(60 * time.Second)
	for range t.C {
		if res, _ := db.Exec("DELETE FROM envelopes WHERE created_at+ttl < ?", time.Now().Unix()); res != nil {
			if n, _ := res.RowsAffected(); n > 0 { log.Printf("purged %d", n) }
		}
	}
}

func hexDecode(s string) ([]byte, error) { return hexDecodeBytes(s) }
func hexDecodeBytes(s string) ([]byte, error) {
	b := make([]byte, len(s)/2)
	for i := 0; i < len(b); i++ { fmt.Sscanf(s[i*2:i*2+2], "%02x", &b[i]) }
	return b, nil
}
func getClientIP(r *http.Request) string {
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" { return strings.Split(xff, ",")[0] }
	ip, _, _ := net.SplitHostPort(r.RemoteAddr); return ip
}

func main() {
	port := os.Getenv("PORT"); if port == "" { port = defaultPort }
	dbPath := os.Getenv("DB_PATH"); if dbPath == "" { dbPath = "relay.db" }
	var err error
	db, err = sql.Open("sqlite", dbPath+"?_pragma=journal_mode(WAL)&_pragma=synchronous(NORMAL)")
	if err != nil { log.Fatal(err) }
	db.Exec("CREATE TABLE IF NOT EXISTS envelopes (id TEXT PRIMARY KEY, inbox_id TEXT, ciphertext BLOB, created_at INTEGER, ttl INTEGER, size_class TEXT)")
	db.Exec("CREATE INDEX IF NOT EXISTS idx_ttl ON envelopes (created_at+ttl)")
	go ttlSweeper()

	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", healthz)
	mux.HandleFunc("POST /envelope", postEnvelope)
	mux.HandleFunc("GET /envelope/{id}", getEnvelope)
	mux.HandleFunc("DELETE /envelope/{id}", deleteEnvelope)

	srv := &http.Server{Addr: ":" + port, Handler: mux, ReadTimeout: 15 * time.Second, WriteTimeout: 15 * time.Second}
	log.Printf("Tote relay :%s (db: %s)", port, dbPath)
	log.Fatal(srv.ListenAndServe())
}
