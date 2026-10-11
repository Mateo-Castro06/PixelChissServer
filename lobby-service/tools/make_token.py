import sys, time, jwt

user_id = sys.argv[1] if len(sys.argv) > 1 else "1"
key = open("../auth-service/keys/private_key.pem").read()
now = int(time.time())
print(jwt.encode(
    {"sub": user_id, "email": f"user{user_id}@test.com", "iat": now, "exp": now + 3600},
    key, algorithm="RS256"))