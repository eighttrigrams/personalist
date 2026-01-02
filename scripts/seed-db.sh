#!/bin/bash

API_BASE="http://localhost:3017"

echo "Seeding database..."

echo "Creating personas..."
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"name":"alice","email":"alice@example.com"}' \
  "$API_BASE/api/personas" > /dev/null

curl -s -X POST -H "Content-Type: application/json" \
  -d '{"name":"bob","email":"bob@example.com"}' \
  "$API_BASE/api/personas" > /dev/null

echo "Creating identities for alice..."

curl -s -X POST -H "Content-Type: application/json" \
  -d '{"id":"x7k9m2","name":"Biography","text":"I am a software engineer.","valid_from":"2025-12-25T10:00:00Z"}' \
  "$API_BASE/api/personas/alice/identities" > /dev/null

curl -s -X PUT -H "Content-Type: application/json" \
  -d '{"name":"Biography","text":"I am a software engineer working on distributed systems.","valid_from":"2025-12-26T14:00:00Z"}' \
  "$API_BASE/api/personas/alice/identities/x7k9m2" > /dev/null

curl -s -X PUT -H "Content-Type: application/json" \
  -d '{"name":"Professional Biography","text":"I am a senior software engineer specializing in distributed systems and databases.","valid_from":"2025-12-28T09:00:00Z"}' \
  "$API_BASE/api/personas/alice/identities/x7k9m2" > /dev/null

curl -s -X POST -H "Content-Type: application/json" \
  -d '{"id":"p3n8v5","name":"Career Goals","text":"Learn Rust this year.","valid_from":"2025-12-25T11:00:00Z"}' \
  "$API_BASE/api/personas/alice/identities" > /dev/null

curl -s -X PUT -H "Content-Type: application/json" \
  -d '{"name":"Career Goals","text":"Learn Rust and contribute to open source.","valid_from":"2025-12-27T16:00:00Z"}' \
  "$API_BASE/api/personas/alice/identities/p3n8v5" > /dev/null

curl -s -X PUT -H "Content-Type: application/json" \
  -d '{"name":"Professional Aspirations","text":"Master Rust, contribute to open source, and give a conference talk.","valid_from":"2025-12-29T10:00:00Z"}' \
  "$API_BASE/api/personas/alice/identities/p3n8v5" > /dev/null

echo "Creating identities for bob..."

curl -s -X POST -H "Content-Type: application/json" \
  -d '{"id":"q4w2r8","name":"About Me","text":"Designer and artist.","valid_from":"2025-12-24T08:00:00Z"}' \
  "$API_BASE/api/personas/bob/identities" > /dev/null

curl -s -X PUT -H "Content-Type: application/json" \
  -d '{"name":"About Me","text":"UI/UX designer with a passion for accessibility.","valid_from":"2025-12-26T12:00:00Z"}' \
  "$API_BASE/api/personas/bob/identities/q4w2r8" > /dev/null

curl -s -X PUT -H "Content-Type: application/json" \
  -d '{"name":"Professional Profile","text":"Senior UI/UX designer focusing on accessible and inclusive design systems.","valid_from":"2025-12-28T15:00:00Z"}' \
  "$API_BASE/api/personas/bob/identities/q4w2r8" > /dev/null

curl -s -X POST -H "Content-Type: application/json" \
  -d '{"id":"h6j1t9","name":"Personal Motto","text":"Keep it simple.","valid_from":"2025-12-25T09:00:00Z"}' \
  "$API_BASE/api/personas/bob/identities" > /dev/null

curl -s -X PUT -H "Content-Type: application/json" \
  -d '{"name":"Core Belief","text":"Simple is beautiful, but never boring.","valid_from":"2025-12-27T11:00:00Z"}' \
  "$API_BASE/api/personas/bob/identities/h6j1t9" > /dev/null

curl -s -X PUT -H "Content-Type: application/json" \
  -d '{"name":"My Core Belief","text":"Simplicity is the ultimate sophistication.","valid_from":"2025-12-29T08:00:00Z"}' \
  "$API_BASE/api/personas/bob/identities/h6j1t9" > /dev/null

echo "Creating relations..."

# Alice: Link Career Goals to Biography (created Dec 27, so won't show before that)
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"source_id":"p3n8v5","valid_from":"2025-12-27T12:00:00Z"}' \
  "$API_BASE/api/personas/alice/identities/x7k9m2/relations" > /dev/null

# Bob: Link Personal Motto to About Me (created Dec 28, so won't show before that)
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"source_id":"h6j1t9","valid_from":"2025-12-28T10:00:00Z"}' \
  "$API_BASE/api/personas/bob/identities/q4w2r8/relations" > /dev/null

echo "Database seeded successfully!"
echo ""
echo "Created:"
echo "  - alice (alice@example.com)"
echo "    - x7k9m2 (Biography): 3 versions (Dec 25-28)"
echo "    - p3n8v5 (Career Goals): 3 versions (Dec 25-29)"
echo "    - Relation: Career Goals -> Biography (from Dec 27)"
echo "  - bob (bob@example.com)"
echo "    - q4w2r8 (About Me): 3 versions (Dec 24-28)"
echo "    - h6j1t9 (Personal Motto): 3 versions (Dec 25-29)"
echo "    - Relation: Personal Motto -> About Me (from Dec 28)"
echo ""
echo "Time travel test: View alice's Biography and travel back to Dec 26 - the relation won't appear!"
