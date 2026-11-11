#!/usr/bin/env python3
"""Check pairing and HTTP access controls without sending projector keypresses."""
import argparse
import datetime
import getpass
import json
from pathlib import Path
import urllib.error
import urllib.request

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--url',required=True,help='Explicit projector HTTP URL; never auto-connect during publication')
    p.add_argument('--pin-file',type=Path)
    p.add_argument('--report',type=Path)
    args=p.parse_args();base=args.url.rstrip('/')
    pin=args.pin_file.read_text().strip() if args.pin_file else getpass.getpass('Pairing code on projector: ')
    checks=[];token=None
    def request(path,data=None,session=None,origin=None,host=None):
        headers={}
        if data is not None:headers.update({'Content-Type':'application/json','Origin':origin or base})
        if session:headers['Authorization']='Bearer '+session
        if host:headers['Host']=host
        req=urllib.request.Request(base+path,data=None if data is None else json.dumps(data).encode(),headers=headers)
        try:response=urllib.request.urlopen(req,timeout=10)
        except urllib.error.HTTPError as e:response=e
        with response:return response.status,response.read(),dict(response.headers)
    def check(name,status,expected):
        if status!=expected:raise RuntimeError(name+': unexpected HTTP '+str(status))
        checks.append({'check':name,'result':'passed','http_status':status})
    try:
        status,body,headers=request('/');check('page available',status,200)
        if pin.encode() in body:raise RuntimeError('Pairing code exposed in page')
        if "frame-ancestors 'none'" not in headers.get('Content-Security-Policy',''):raise RuntimeError('CSP missing')
        status,_,_=request('/api/status');check('state requires pairing',status,401)
        status,_,_=request('/api/key',{'key':'left'});check('keypress requires pairing',status,401)
        status,_,_=request('/api/text',{'text':'test'});check('typing requires pairing',status,401)
        status,body,_=request('/api/pair',{'pin':pin});check('valid pairing',status,200);token=json.loads(body)['token']
        status,body,_=request('/api/status',session=token);check('authenticated state',status,200)
        if not json.loads(body)['ready']:raise RuntimeError('Input backend not ready')
        status,_,_=request('/api/key',{'key':'left'},token,origin='https://example.org');check('foreign origin blocked',status,403)
        status,_,_=request('/api/key',{'key':'left'},token,host='example.org:8080');check('foreign host blocked',status,403)
        status,_,_=request('/api/key',{'key':'not-a-button'},token);check('unknown commands blocked',status,400)
        status,_,_=request('/api/key',{'key':'left','command':'id'},token);check('extra command parameters blocked',status,400)
        status,_,_=request('/api/text',{'text':'test'},token,origin='https://example.org');check('typing rejects foreign origin',status,403)
        for name,data in [('empty text',{'text':''}),('oversized text',{'text':'a'*257}),('non-string text',{'text':42}),('unsupported alphabet',{'text':'тест'}),('control characters',{'text':'test\n'}),('extra text parameters',{'text':'test','command':'id'})]:
            status,_,_=request('/api/text',data,token);check(name+' rejected without input',status,400)
        status,_,_=request('/api/logout',{},token);check('test session logout',status,200)
        status,_,_=request('/api/status',session=token);check('revoked session blocked',status,401);token=None
    finally:
        if token:
            try:request('/api/logout',{},token)
            except Exception:pass
    report={'at':datetime.datetime.now().astimezone().isoformat(),'checks':checks,'ready':True,'input_keys_sent':False}
    if args.report:args.report.parent.mkdir(exist_ok=True,parents=True);args.report.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(str(len(checks))+' HTTP checks passed; no input keys sent')

if __name__=='__main__':main()
