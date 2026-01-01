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
  -d '{"id":"bio","text":"I am a software engineer.","valid_from":"2025-12-25T10:00:00Z"}' \
  "$API_BASE/api/personas/alice/identities" > /dev/null

curl -s -X PUT -H "Content-Type: application/json" \
  -d '{"text":"I am a software engineer working on distributed systems.","valid_from":"2025-12-26T14:00:00Z"}' \
  "$API_BASE/api/personas/alice/identities/bio" > /dev/null

curl -s -X PUT -H "Content-Type: application/json" \
  -d '{"text":"I am a senior software engineer specializing in distributed systems and databases.","valid_from":"2025-12-28T09:00:00Z"}' \
  "$API_BASE/api/personas/alice/identities/bio" > /dev/null

curl -s -X POST -H "Content-Type: application/json" \
  -d '{"id":"goals","text":"Learn Rust this year.","valid_from":"2025-12-25T11:00:00Z"}' \
  "$API_BASE/api/personas/alice/identities" > /dev/null

curl -s -X PUT -H "Content-Type: application/json" \
  -d '{"text":"Learn Rust and contribute to open source.","valid_from":"2025-12-27T16:00:00Z"}' \
  "$API_BASE/api/personas/alice/identities/goals" > /dev/null

curl -s -X PUT -H "Content-Type: application/json" \
  -d '{"text":"Master Rust, contribute to open source, and give a conference talk.","valid_from":"2025-12-29T10:00:00Z"}' \
  "$API_BASE/api/personas/alice/identities/goals" > /dev/null

echo "Creating identities for bob..."

curl -s -X POST -H "Content-Type: application/json" \
  -d '{"id":"bio","text":"Designer and artist.","valid_from":"2025-12-24T08:00:00Z"}' \
  "$API_BASE/api/personas/bob/identities" > /dev/null

curl -s -X PUT -H "Content-Type: application/json" \
  -d '{"text":"UI/UX designer with a passion for accessibility.","valid_from":"2025-12-26T12:00:00Z"}' \
  "$API_BASE/api/personas/bob/identities/bio" > /dev/null

curl -s -X PUT -H "Content-Type: application/json" \
  -d '{"text":"Senior UI/UX designer focusing on accessible and inclusive design systems.","valid_from":"2025-12-28T15:00:00Z"}' \
  "$API_BASE/api/personas/bob/identities/bio" > /dev/null

curl -s -X POST -H "Content-Type: application/json" \
  -d '{"id":"motto","text":"Keep it simple.","valid_from":"2025-12-25T09:00:00Z"}' \
  "$API_BASE/api/personas/bob/identities" > /dev/null

curl -s -X PUT -H "Content-Type: application/json" \
  -d '{"text":"Simple is beautiful, but never boring.","valid_from":"2025-12-27T11:00:00Z"}' \
  "$API_BASE/api/personas/bob/identities/motto" > /dev/null

curl -s -X PUT -H "Content-Type: application/json" \
  -d '{"text":"Simplicity is the ultimate sophistication.","valid_from":"2025-12-29T08:00:00Z"}' \
  "$API_BASE/api/personas/bob/identities/motto" > /dev/null

echo "Database seeded successfully!"
echo ""
echo "Created:"
echo "  - alice (alice@example.com)"
echo "    - bio: 3 versions (Dec 25-28)"
echo "    - goals: 3 versions (Dec 25-29)"
echo "  - bob (bob@example.com)"
echo "    - bio: 3 versions (Dec 24-28)"
echo "    - motto: 3 versions (Dec 25-29)"
