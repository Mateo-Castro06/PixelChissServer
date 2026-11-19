"""Verify actual TLS/WSS against an isolated Gateway on a non-loopback host address.

Requires PROOF_ISOLATED=1, PROOF_GATEWAY=https://<private-IP>:<port>,
PROOF_CA_FILE, PROOF_REFRESH_TOKEN and PROOF_SECOND_REFRESH_TOKEN.
This is a single-host/network-address probe, not a physical Windows/Linux LAN test.
Google start/PKCE is checked; it does not log in to a real Google account.
"""
import asyncio
import base64
import hashlib
import ipaddress
import json
import os
import secrets
import socket
import ssl
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlsplit
from urllib.request import Request, build_opener, HTTPSHandler, HTTPRedirectHandler

import websockets
from websockets.exceptions import InvalidStatusCode

BASE = os.environ.get('PROOF_GATEWAY', '')
CA = os.environ.get('PROOF_CA_FILE', '')
LOCAL = os.environ.get('PROOF_LOCAL_GATEWAY', 'http://127.0.0.1:18080')


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def request(base, path, method='GET', body=None, token=None, expected=200):
    headers = {'Accept': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers['Content-Type'] = 'application/json'
    opener = build_opener(NoRedirect, HTTPSHandler(context=ssl.create_default_context(cafile=CA)))
    try:
        response = opener.open(Request(base+path, data=data, headers=headers, method=method), timeout=8)
    except HTTPError as error:
        response = error
    with response:
        assert response.status == expected, (path, response.status, expected)
        raw = response.read()
        value = json.loads(raw) if raw and 'json' in response.headers.get('Content-Type', '') else None
        return value, dict(response.headers)


async def sockets(tokens, actors):
    ws_base = 'wss' + BASE.removeprefix('https') + '/api/game/ws'
    async def client(token, actor):
        async with websockets.connect(ws_base, ssl=ssl.create_default_context(cafile=CA)) as ws:
            await ws.send(json.dumps({'type':'AUTH','token':token}))
            result=json.loads(await asyncio.wait_for(ws.recv(),5))
            assert result['type']=='AUTHENTICATED' and result['playerId']==actor
    await asyncio.gather(*(client(t,a) for t,a in zip(tokens,actors)))
    async with websockets.connect(ws_base,ssl=ssl.create_default_context(cafile=CA)) as ws:
        await ws.send(json.dumps({'type':'AUTH','token':'invalid-fixture'}))
        result=json.loads(await asyncio.wait_for(ws.recv(),5))
        assert result['type']=='ERROR' and result['code']=='INVALID_TOKEN'
    for url, origin, expected in ((ws_base+'?token=invalid-fixture',None,400),(ws_base,'http://untrusted.invalid',403)):
        try:
            async with websockets.connect(url,ssl=ssl.create_default_context(cafile=CA),origin=origin):
                raise AssertionError('Rejected handshake was accepted')
        except InvalidStatusCode as error:
            assert error.status_code==expected


def main():
    parsed=urlsplit(BASE)
    if os.environ.get('PROOF_ISOLATED')!='1' or parsed.scheme!='https' or not CA:
        raise SystemExit('Explicit isolated HTTPS endpoint and CA required')
    address=ipaddress.ip_address(parsed.hostname)
    assert address.version==4 and address.is_private and not address.is_loopback
    endpoint=(parsed.hostname,parsed.port or 443)
    trusted=ssl.create_default_context(cafile=CA)
    with socket.create_connection(endpoint,timeout=5) as raw:
        with trusted.wrap_socket(raw,server_hostname=parsed.hostname) as connection:
            assert connection.version() in ('TLSv1.2','TLSv1.3')
    for context, hostname in ((ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT),parsed.hostname),(trusted,'wrong-host.invalid')):
        try:
            with socket.create_connection(endpoint,timeout=5) as raw:
                with context.wrap_socket(raw,server_hostname=hostname):
                    raise AssertionError('Untrusted certificate/hostname accepted')
        except ssl.SSLCertVerificationError:
            pass
    assert request(BASE,'/actuator/health')[0]['status']=='UP'
    assert request(LOCAL,'/actuator/health')[0]['status']=='UP'
    request(BASE,'/api/game/board',expected=401)
    request(BASE,'/api/game/board',token='invalid-fixture',expected=401)
    try:
        request(BASE.replace('https://','http://'),'/actuator/health')
        raise AssertionError('Plaintext accepted on TLS port')
    except (AssertionError, OSError) as error:
        assert str(error)!='Plaintext accepted on TLS port'
    # Public TLS connector blocks the old Web redirect flow; localhost retains it.
    request(BASE,'/oauth2/authorization/google?client_state=lanproofstate123456&client_port=45678',expected=400)
    verifier=base64.urlsafe_b64encode(secrets.token_bytes(32)).decode().rstrip('=')
    challenge=base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip('=')
    start, headers=request(BASE,'/api/auth/desktop/start','POST',{'clientPort':45678,'codeChallenge':challenge})
    assert headers.get('Cache-Control')=='no-store'
    params=parse_qs(urlsplit(start['authorizationUrl']).query)
    assert params['redirect_uri']==['http://127.0.0.1:45678/callback']
    assert params['code_challenge']==[challenge] and params['code_challenge_method']==['S256']
    assert params['state']==[start['state']] and params['nonce'][0]
    assert not {'client_secret','code_verifier','accessToken','refreshToken'} & params.keys()
    request(BASE,'/api/auth/desktop/exchange','POST',{'state':start['state'],'code':'never-sent-to-google',
                                                   'codeVerifier':'x'*43},expected=401)
    request(BASE,'/api/auth/desktop/start','POST',{'clientPort':80,'codeChallenge':challenge},expected=400)
    _, web_headers=request(LOCAL,'/oauth2/authorization/google?client_state=lanproofstate123456&client_port=45678&client_challenge='+challenge,expected=302)
    assert urlsplit(web_headers['Location']).hostname=='accounts.google.com'
    tokens=[request(BASE,'/api/refresh','POST',{'refreshToken':os.environ[key]})[0]['accessToken']
            for key in ('PROOF_REFRESH_TOKEN','PROOF_SECOND_REFRESH_TOKEN')]
    actors=[str(request(BASE,'/api/auth/me',token=t)[0]['id']) for t in tokens]
    assert len(set(actors))==2
    assert str(request(LOCAL,'/api/auth/me',token=tokens[0])[0]['id'])==actors[0]
    asyncio.run(sockets(tokens,actors))
    # With a JWT too, LAN metrics remain unavailable; public health is intentionally minimal.
    try:
        request(BASE,'/actuator/prometheus',token=tokens[0],expected=404)
    except AssertionError:
        request(BASE,'/actuator/prometheus',token=tokens[0],expected=403)
    print(json.dumps({'targetIsNonLoopbackPrivateAddress':True,'trustedTlsAndHostname':True,
                      'untrustedCARejected':True,'wrongHostnameRejected':True,'plaintextNotAccepted':True,
                      'localHttpPreserved':True,'sameIdentityLocalAndLan':True,'independentJwtSessions':True,
                      'concurrentWss':True,'invalidJwtRejected':True,'wsQueryRejected':True,'originRejected':True,
                      'desktopPkceStart':True,'clientLoopbackRedirect':True,'verifierRejectedBeforeGoogle':True,
                      'legacyWebOnlyLocal':True,'lanMetricsBlocked':True,'realGoogleLoginExecuted':False,
                      'physicalSecondComputerUsed':False}))


if __name__=='__main__':main()
