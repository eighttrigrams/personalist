#!/bin/bash

. "$(dirname "$0")/config.sh"

# Prefer the port the running server wrote — this is normally invoked by that
# server's own pre-seed, and the seed POSTs have to reach it.
if [ -f .server.port ]; then
  PORT=$(cat .server.port)
else
  PORT=$(read_config ":server :port")
fi

API_BASE="http://localhost:$PORT"

echo "Seeding database..."

# An account is an email and a password, and it may hold no personas at all —
# POST /api/accounts creates only the account. So seeding is two steps per user:
# create the account, read its id out of the response, and create the persona
# under it with :account_id. That field is admin's, and this runs in dev with
# :dangerously-skip-logins?, where every caller counts as admin — which is why
# no token is needed here.
create_account() {          # create_account <email> -> prints the new account id
  curl -s -X POST -H "Content-Type: application/json" \
    -d "{\"email\":\"$1\"}" \
    "$API_BASE/api/accounts" \
  | sed -n 's/.*"id":\([0-9]*\).*/\1/p'
}

create_persona() {          # create_persona <account-id> <persona-id> <display name>
  curl -s -X POST -H "Content-Type: application/json" \
    -d "{\"account_id\":$1,\"id\":\"$2\",\"name\":\"$3\"}" \
    "$API_BASE/api/personas" > /dev/null
}

echo "Creating accounts and their personas..."
ALICE_ACCOUNT=$(create_account "alice@example.com")
create_persona "$ALICE_ACCOUNT" "alice" "Alice Johnson"

BOB_ACCOUNT=$(create_account "bob@example.com")
create_persona "$BOB_ACCOUNT" "bob" "Bob Smith"

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

curl -s -X POST -H "Content-Type: application/json" \
  -d '{"target_id":"p3n8v5","valid_from":"2025-12-27T12:00:00Z"}' \
  "$API_BASE/api/personas/alice/identities/x7k9m2/relations" > /dev/null

curl -s -X POST -H "Content-Type: application/json" \
  -d '{"target_id":"q4w2r8","valid_from":"2025-12-28T10:00:00Z"}' \
  "$API_BASE/api/personas/bob/identities/h6j1t9/relations" > /dev/null

echo "Database seeded successfully!"
echo ""
echo "Created:"
echo "  - alice@example.com - Persona: alice - Display: Alice Johnson"
echo "    - x7k9m2 Biography: 3 versions"
echo "    - p3n8v5 Career Goals: 3 versions"
echo "    - Relation: Career Goals -> Biography"
echo "  - bob@example.com - Persona: bob - Display: Bob Smith"
echo "    - q4w2r8 About Me: 3 versions"
echo "    - h6j1t9 Personal Motto: 3 versions"
echo "    - Relation: Professional Profile -> My Core Belief"
echo ""
echo "Time travel test: View Alice's Biography and travel back to Dec 26 - the relation wont appear!"
