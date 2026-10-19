#!/usr/bin/env bash
# Exercises supabase/schema.sql through the public API, exactly as the app does.
# Three anonymous users: A and B become friends through an invite code, C is a stranger.
# Usage: SUPABASE_URL=... SUPABASE_ANON_KEY=... bash supabase/smoke-test.sh
set -u
U="${SUPABASE_URL:?}"; K="${SUPABASE_ANON_KEY:?}"
pass=0; fail=0
check() { if [ "$2" = "$3" ]; then echo "PASS  $1"; pass=$((pass+1)); else echo "FAIL  $1: expected [$3], got [$2]"; fail=$((fail+1)); fi; }
signup() { curl -s -X POST "$U/auth/v1/signup" -H "apikey: $K" -H "Content-Type: application/json" -d '{}' | python -c "import sys,json;print(json.load(sys.stdin)['access_token'])"; }
rpc() { curl -s -X POST "$U/rest/v1/rpc/$2" -H "apikey: $K" -H "Authorization: Bearer $1" -H "Content-Type: application/json" -d "${3:-{\}}"; }
get() { curl -s "$U/rest/v1/$2" -H "apikey: $K" -H "Authorization: Bearer $1"; }
j() { python -c "import sys,json;d=json.load(sys.stdin);print($1)"; }
today=$(date +%F)

A=$(signup); B=$(signup); C=$(signup)
check "three anonymous sign-ins" "$([ -n "$A" ] && [ -n "$B" ] && [ -n "$C" ] && echo ok)" ok

codeA=$(rpc "$A" upsert_profile '{"p_username":"smoke_a","p_full_name":"Smoke A"}' | j "d['invite_code']")
rpc "$B" upsert_profile '{"p_username":"smoke_b","p_full_name":null}' >/dev/null
rpc "$C" upsert_profile '{"p_username":"smoke_c","p_full_name":"C"}' >/dev/null
check "invite code is 8 chars" "${#codeA}" 8

check "bad username rejected" "$(rpc "$A" upsert_profile '{"p_username":"NO SPACES!","p_full_name":null}' | j "'rejected' if 'message' in d else 'accepted'")" rejected
check "own code rejected" "$(rpc "$A" redeem_invite "{\"p_code\":\"$codeA\"}" | j "d.get('message')")" "that is your own code"
check "unknown code rejected" "$(rpc "$B" redeem_invite '{"p_code":"ZZZZZZZZ"}' | j "d.get('message')")" "invalid code"
check "B redeems A's code" "$(rpc "$B" redeem_invite "{\"p_code\":\"${codeA,,}\"}" | j "d[0]['ig_username']")" smoke_a
check "redeeming twice is harmless" "$(rpc "$B" redeem_invite "{\"p_code\":\"$codeA\"}" | j "len(d)")" 1

rpc "$A" push_stats "{\"p_day\":\"$today\",\"p_reels\":12,\"p_seconds\":300}" >/dev/null
rpc "$A" push_stats "{\"p_day\":\"$today\",\"p_reels\":7,\"p_seconds\":100}" >/dev/null   # late, smaller: ignored
check "totals never go down" "$(get "$A" "daily_stats?select=reels&day=eq.$today" | j "d[0]['reels']")" 12
check "far-off day rejected" "$(rpc "$A" push_stats '{"p_day":"2000-01-01","p_reels":1,"p_seconds":1}' | j "d.get('message')")" "day out of range"
check "absurd count rejected" "$(rpc "$A" push_stats "{\"p_day\":\"$today\",\"p_reels\":99999,\"p_seconds\":1}" | j "'rejected' if 'message' in d else 'accepted'")" rejected

check "B sees A in friends_stats" "$(rpc "$B" friends_stats | j "[(f['ig_username'],f['reels']) for f in d]")" "[('smoke_a', 12)]"
check "A sees B (both ways)" "$(rpc "$A" friends_stats | j "[f['ig_username'] for f in d]")" "['smoke_b']"
check "C sees no friends" "$(rpc "$C" friends_stats | j "len(d)")" 0
check "C can't read A's stats" "$(get "$C" "daily_stats?select=reels" | j "len(d)")" 0
check "C can't read A's profile" "$(get "$C" "profiles?select=ig_username" | j "[p['ig_username'] for p in d]")" "['smoke_c']"
check "C can't write tables directly" "$(curl -s -X POST "$U/rest/v1/daily_stats" -H "apikey: $K" -H "Authorization: Bearer $C" -H "Content-Type: application/json" -d "{\"user_id\":\"00000000-0000-0000-0000-000000000000\",\"day\":\"$today\",\"reels\":1}" | j "'denied' if 'message' in d else 'allowed'")" denied
check "anon key alone can't call RPCs" "$(rpc "$K" friends_stats | j "'denied' if isinstance(d,dict) else 'allowed'")" denied

aid=$(get "$A" "profiles?select=id&ig_username=eq.smoke_a" | j "d[0]['id']")
rpc "$B" remove_friend "{\"p_friend\":\"$aid\"}" >/dev/null
check "unfriend removes both sides" "$(rpc "$A" friends_stats | j "len(d)")" 0

echo "---- $pass passed, $fail failed"
[ "$fail" = 0 ]
