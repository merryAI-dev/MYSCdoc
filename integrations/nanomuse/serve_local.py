"""Full nanoMuse local app with MYSCdoc retrieval and direct Claude models.
Cloud relay/account sync/update services are not available in this distribution.
"""
from pathlib import Path
import os,sys
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(Path(__file__).parent/'vendor'))
# Only explicit model credentials from the user's authorized local env.
from dotenv import load_dotenv
load_dotenv(ROOT / '.env', override=False)
if not os.environ.get('ANTHROPIC_API_KEY', '').startswith('sk-ant-'):
    raise SystemExit('Set ANTHROPIC_API_KEY in .env or the environment before starting Muse.')
os.environ['MYSC_MEMBER_ID']=os.environ.get('MYSC_MEMBER_ID','762538ab-40e5-47fc-a3a2-83ba0d729001')
os.environ['NANOMUSE_NO_UPDATE_CHECK']='1'
os.umask(0o077)
from nanomuse.config import Settings
from nanomuse.server.service import MuseService
from nanomuse.server.api import create_app
from nanomuse.tools.myscdoc import MYSCdocSearch
from starlette.responses import JSONResponse
import uvicorn

data=ROOT/'.nanomuse-local'
data.mkdir(exist_ok=True)
settings=Settings(data_dir=data,log_level='WARNING',
    llm={'provider':'anthropic','base_url':'https://api.anthropic.com','api_key':'configured-in-server-environment','model':os.environ.get('ANTHROPIC_CHAT_MODEL','claude-haiku-5-5'),'max_tokens':4096,'tool_mode':'native','vision':'on'},
    agent={'name':'MYSC Muse','language':'Korean','workspace':data/'workspace','max_steps':12,'instructions':'MYSCdoc 업무 기록 질문에는 myscdoc_search로 먼저 근거를 검색하세요. 기업·사건·조건·부정·예정 여부를 구분하고 원문 문서 제목을 인용하세요. 성장·성과·성숙 자동 분류는 미검토입니다. 근거가 없으면 근거 부족을 명시하세요. 화면·파일·브라우저 작업은 사용자 요청과 Sentinel 승인을 따라 수행하세요. nanoMuse Cloud 및 원격 동기화는 비활성 상태입니다.'},
    memory={'enabled':True,'embeddings':'off'},
    cloud={'base_url':'http://127.0.0.1:9','required':False,'models':False,'sync':False},
    hub={'enabled':False,'remote_control':False},
    hands={'enabled':True},browser={'enabled':True},
    sentinel={'mode':'strict','always_ask_tools':['shell','python_execute','computer_act','computer_task','phone_act','phone_task'],'egress_allowlist':[]},
    server={'host':'127.0.0.1','port':8787,'auth':True,'update_check':False})
svc=MuseService(settings)
svc.app.tools.add(MYSCdocSearch())
# Tools share the collection; retained on all new and existing local threads.
svc.profile.proactivity='off'
svc.connections.set_onboarded(True)
app=create_app(settings,service=svc)
@app.middleware('http')
async def local_only(request,call_next):
    path=request.url.path
    blocked=('/api/cloud','/api/hub','/api/sync','/api/chatgpt','/api/push','/api/providers','/api/llm/models','/api/connections/llm','/api/connections/gui','/api/connections/endpoints','/api/connections/embeddings','/api/connections/image','/api/connections/video')
    if any(path==p or path.startswith(p+'/') for p in blocked):
        return JSONResponse({'detail':'MYSCdoc local mode: this external service is disabled'},status_code=403)
    if path=='/api/nudges':return JSONResponse({'enabled':False})
    if path=='/api/update':return JSONResponse({'enabled':False,'available':False})
    response=await call_next(request)
    response.headers['Referrer-Policy']='no-referrer'
    response.headers['Content-Security-Policy']="frame-ancestors 'self' http://127.0.0.1:8086"
    return response
if __name__=='__main__':uvicorn.run(app,host='127.0.0.1',port=8787,access_log=False)
