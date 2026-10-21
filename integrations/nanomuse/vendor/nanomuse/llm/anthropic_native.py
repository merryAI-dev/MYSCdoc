"""MYSCdoc: direct Anthropic Messages adapter, including vision and native tool calls.
No relay, alternate endpoint, proxy environment, or provider fallback is used.
"""
from __future__ import annotations
import json
import os
import httpx
from nanomuse.llm.base import BaseLLM
from nanomuse.llm.vision import image_data_url
from nanomuse.schema import LLMResponse, ToolCall, Function, Role

class AnthropicNativeLLM(BaseLLM):
    name = 'anthropic'
    vision_available = True
    def __init__(self, settings):
        self.settings = settings
        self.model = os.environ.get('ANTHROPIC_CHAT_MODEL', 'claude-haiku-5-5')
        self._key = os.environ.get('ANTHROPIC_API_KEY','')
        if not self._key: raise ValueError('ANTHROPIC_API_KEY is required')
        self.client = httpx.AsyncClient(timeout=settings.timeout, follow_redirects=False, trust_env=False)

    async def ask(self, messages, tools=None, tool_choice='auto', on_delta=None, max_tokens=None):
        system='\n'.join(m.content or '' for m in messages if m.role==Role.SYSTEM)
        wire=[]
        for m in messages:
            if m.role==Role.SYSTEM:continue
            if m.role==Role.TOOL:
                role='user';content=[{'type':'tool_result','tool_use_id':m.tool_call_id,'content':m.content or ''}]
            else:
                role=m.role.value;content=[]
                if m.content:content.append({'type':'text','text':m.content})
                for path in m.images or []:
                    url=image_data_url(path)
                    if url:
                        header,data=url.split(',',1)
                        content.append({'type':'image','source':{'type':'base64','media_type':header[5:].split(';')[0],'data':data}})
                for c in m.tool_calls or []:
                    content.append({'type':'tool_use','id':c.id,'name':c.name,'input':c.arguments})
            if not content:continue
            if wire and wire[-1]['role']==role:wire[-1]['content'].extend(content)
            else:wire.append({'role':role,'content':content})
        payload={'model':self.model,'max_tokens':min(max_tokens or self.settings.max_tokens,8192),'thinking':{'type':'disabled'},'system':system,'messages':wire}
        if tools and tool_choice!='none':
            payload['tools']=[{'name':t['function']['name'],'description':t['function'].get('description',''),'input_schema':t['function']['parameters']} for t in tools]
            payload['tool_choice']={'type':'any' if tool_choice=='required' else 'auto'}
        r=await self.client.post('https://api.anthropic.com/v1/messages',headers={'x-api-key':self._key,'anthropic-version':'2023-06-01'},json=payload)
        if r.status_code!=200:raise RuntimeError(f'Claude API request failed (HTTP {r.status_code})')
        out=r.json();stop=out.get('stop_reason')
        if stop not in ('end_turn','tool_use','max_tokens'):raise RuntimeError('Unexpected Claude response')
        text='\n'.join(x['text'] for x in out['content'] if x['type']=='text')
        calls=[ToolCall(id=x['id'],function=Function(name=x['name'],arguments=json.dumps(x['input']))) for x in out['content'] if x['type']=='tool_use']
        if on_delta and text:on_delta(text)
        return LLMResponse(content=text,tool_calls=calls,finish_reason='length' if stop=='max_tokens' else 'tool_calls' if calls else 'stop',usage=out.get('usage',{}),model=self.model)

    async def close(self):await self.client.aclose()
