"""Read-only bridge to the user's existing, authenticated MYSCdoc instance."""
import json
import os
import httpx
from pydantic import Field
from nanomuse.tools.base import BaseTool, CallAssessment
from nanomuse.schema import ToolResult

class MYSCdocSearch(BaseTool):
    name: str = 'myscdoc_search'
    description: str = 'MYSCdoc 해양수산 지식그래프에서 근거를 검색합니다. 기업명·조건을 포함한 검색어를 사용하고 필요하면 다른 검색어로 다시 조회하세요. 자동 분류는 미검토입니다.'
    parameters: dict = Field(default_factory=lambda:{'type':'object','properties':{'query':{'type':'string','maxLength':4000}},'required':['query'],'additionalProperties':False})
    reads_private_data: bool = True
    egress: bool = True
    def assess(self,args):
        return CallAssessment(reads_private_data=True,egress=True,egress_target='127.0.0.1',egress_configured=True,summary='MYSCdoc 지식그래프 근거 조회')
    async def execute(self,query='',**kwargs):
        if not isinstance(query,str) or not query.strip() or len(query)>4000 or kwargs:return ToolResult.fail('Invalid query')
        member=os.environ.get('MYSC_MEMBER_ID','')
        if not member:return ToolResult.fail('MYSCdoc member configuration missing')
        try:
            async with httpx.AsyncClient(timeout=15,trust_env=False,follow_redirects=False) as client:
                r=await client.get('http://127.0.0.1:8086/api/knowledge/agent/evidence',params={'q':query},headers={'X-Member-Id':member})
            if r.status_code!=200:return ToolResult.fail('MYSCdoc search unavailable')
            return ToolResult(output=json.dumps(r.json(),ensure_ascii=False))
        except Exception:return ToolResult.fail('MYSCdoc search unavailable')
