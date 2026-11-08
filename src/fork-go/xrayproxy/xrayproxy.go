// entiny: xrayproxy — SOCKS5 wrapper over Xray-core for VLESS (Reality / TLS / WS / gRPC).
package xrayproxy

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"strconv"
	"strings"
	"sync"

	"github.com/xtls/xray-core/core"

	_ "github.com/xtls/xray-core/app/dispatcher"
	_ "github.com/xtls/xray-core/app/policy"
	_ "github.com/xtls/xray-core/app/proxyman/inbound"
	_ "github.com/xtls/xray-core/app/proxyman/outbound"
	_ "github.com/xtls/xray-core/app/router"
	_ "github.com/xtls/xray-core/app/stats"
	_ "github.com/xtls/xray-core/main/json"
	_ "github.com/xtls/xray-core/proxy/freedom"
	_ "github.com/xtls/xray-core/proxy/socks"
	_ "github.com/xtls/xray-core/proxy/vless/outbound"
	_ "github.com/xtls/xray-core/transport/internet/grpc"
	_ "github.com/xtls/xray-core/transport/internet/httpupgrade"
	_ "github.com/xtls/xray-core/transport/internet/reality"
	_ "github.com/xtls/xray-core/transport/internet/tcp"
	_ "github.com/xtls/xray-core/transport/internet/tls"
	_ "github.com/xtls/xray-core/transport/internet/udp"
	_ "github.com/xtls/xray-core/transport/internet/websocket"
	_ "golang.org/x/mobile/bind"
)

var (
	mu       sync.Mutex
	instance *core.Instance
)

// Start brings up a local SOCKS5 inbound on 127.0.0.1:socksPort that dials through the given vless:// URI.
func Start(vlessURI string, socksPort int32) error {
	mu.Lock()
	defer mu.Unlock()
	if instance != nil {
		return errors.New("xray: already running")
	}
	cfgJSON, err := buildConfig(vlessURI, int(socksPort))
	if err != nil {
		return err
	}
	cfg, err := core.LoadConfig("json", strings.NewReader(cfgJSON))
	if err != nil {
		return fmt.Errorf("xray: load config: %w", err)
	}
	inst, err := core.New(cfg)
	if err != nil {
		return fmt.Errorf("xray: new instance: %w", err)
	}
	if err := inst.Start(); err != nil {
		return fmt.Errorf("xray: start: %w", err)
	}
	instance = inst
	return nil
}

func Stop() error {
	mu.Lock()
	defer mu.Unlock()
	if instance == nil {
		return nil
	}
	err := instance.Close()
	instance = nil
	return err
}

func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return instance != nil
}

type vlessParams struct {
	uuid        string
	host        string
	port        int
	flow        string
	encryption  string
	network     string
	security    string
	sni         string
	fingerprint string
	publicKey   string
	shortID     string
	spiderX     string
	path        string
	wsHost      string
	service     string
}

func parseVless(raw string) (*vlessParams, error) {
	raw = strings.TrimSpace(raw)
	if !strings.HasPrefix(strings.ToLower(raw), "vless://") {
		return nil, errors.New("xray: not a vless link")
	}
	u, err := url.Parse(raw)
	if err != nil {
		return nil, fmt.Errorf("xray: parse link: %w", err)
	}
	p := &vlessParams{
		uuid:    u.User.Username(),
		host:    u.Hostname(),
		spiderX: "/",
	}
	if p.uuid == "" || p.host == "" {
		return nil, errors.New("xray: link without id or host")
	}
	port, err := strconv.Atoi(u.Port())
	if err != nil || port <= 0 || port > 65535 {
		return nil, errors.New("xray: link without valid port")
	}
	p.port = port

	q := u.Query()
	p.flow = q.Get("flow")
	p.encryption = q.Get("encryption")
	if p.encryption == "" {
		p.encryption = "none"
	}
	p.network = q.Get("type")
	if p.network == "" {
		p.network = "tcp"
	}
	p.security = q.Get("security")
	if p.security == "" {
		p.security = "none"
	}
	p.sni = q.Get("sni")
	if p.sni == "" {
		p.sni = q.Get("peer")
	}
	p.fingerprint = q.Get("fp")
	p.publicKey = q.Get("pbk")
	p.shortID = q.Get("sid")
	if spx := q.Get("spx"); spx != "" {
		p.spiderX = spx
	}
	p.path = q.Get("path")
	p.wsHost = q.Get("host")
	p.service = q.Get("serviceName")
	if p.security == "reality" && p.publicKey == "" {
		return nil, errors.New("xray: reality link without pbk")
	}
	return p, nil
}

func buildConfig(raw string, socksPort int) (string, error) {
	p, err := parseVless(raw)
	if err != nil {
		return "", err
	}
	user := map[string]any{"id": p.uuid, "encryption": p.encryption}
	if p.flow != "" {
		user["flow"] = p.flow
	}
	cfg := map[string]any{
		"log": map[string]any{"loglevel": "warning"},
		"inbounds": []any{map[string]any{
			"tag":      "socks-in",
			"listen":   "127.0.0.1",
			"port":     socksPort,
			"protocol": "socks",
			"settings": map[string]any{"auth": "noauth", "udp": true},
		}},
		"outbounds": []any{
			map[string]any{
				"tag":      "proxy",
				"protocol": "vless",
				"settings": map[string]any{"vnext": []any{map[string]any{
					"address": p.host,
					"port":    p.port,
					"users":   []any{user},
				}}},
				"streamSettings": streamSettings(p),
			},
			map[string]any{"tag": "direct", "protocol": "freedom"},
		},
	}
	b, err := json.Marshal(cfg)
	if err != nil {
		return "", err
	}
	return string(b), nil
}

func streamSettings(p *vlessParams) map[string]any {
	s := map[string]any{"network": p.network, "security": p.security}
	switch p.security {
	case "reality":
		r := map[string]any{
			"serverName":  p.sni,
			"publicKey":   p.publicKey,
			"shortId":     p.shortID,
			"spiderX":     p.spiderX,
			"fingerprint": p.fingerprint,
		}
		if p.fingerprint == "" {
			r["fingerprint"] = "chrome"
		}
		s["realitySettings"] = r
	case "tls":
		t := map[string]any{"serverName": p.sni}
		if p.fingerprint != "" {
			t["fingerprint"] = p.fingerprint
		}
		s["tlsSettings"] = t
	}
	switch p.network {
	case "ws":
		ws := map[string]any{}
		if p.path != "" {
			ws["path"] = p.path
		}
		if p.wsHost != "" {
			ws["headers"] = map[string]any{"Host": p.wsHost}
		}
		s["wsSettings"] = ws
	case "grpc":
		g := map[string]any{}
		if p.service != "" {
			g["serviceName"] = p.service
		}
		s["grpcSettings"] = g
	case "httpupgrade":
		h := map[string]any{"path": p.path}
		if p.wsHost != "" {
			h["host"] = p.wsHost
		}
		s["httpupgradeSettings"] = h
	}
	return s
}
