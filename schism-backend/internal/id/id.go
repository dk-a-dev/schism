package id

import "crypto/rand"

const alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_-"

// New returns a random 12-char id from [A-Za-z0-9_-], opaque URL-safe id.
func New() string {
	b := make([]byte, 12)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	for i := range b {
		b[i] = alphabet[int(b[i])&63]
	}
	return string(b)
}
