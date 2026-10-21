import sys,os,json,unittest,tempfile
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).parent/'vendor'))
import httpx
from PIL import Image
from nanomuse.config import LLMSettings, SentinelSettings
from nanomuse.llm.factory import create_llm
from nanomuse.llm.anthropic_native import AnthropicNativeLLM
from nanomuse.schema import Message, ToolCall, Function, RiskLevel
from nanomuse.cloud import CloudClient, CloudError
from nanomuse.tools.shell import scrubbed_env
from nanomuse.tools.myscdoc import MYSCdocSearch
from nanomuse.sentinel.policy import Policy, Decision
from nanomuse.tools.base import CallAssessment

class LocalTests(unittest.IsolatedAsyncioTestCase):
    async def test_factory_uses_only_direct_claude(self):
        with patch.dict(os.environ,{'ANTHROPIC_API_KEY':'test-only'}):
            llm=create_llm(LLMSettings())
            self.assertIsInstance(llm,AnthropicNativeLLM)
            await llm.close()
    async def test_vision_and_tool_results_preserved_without_key_in_body(self):
        with patch.dict(os.environ,{'ANTHROPIC_API_KEY':'test-only'}):llm=AnthropicNativeLLM(LLMSettings())
        await llm.client.aclose()
        def respond(req):
            self.assertEqual(str(req.url),'https://api.anthropic.com/v1/messages')
            body=json.loads(req.content)
            self.assertEqual(req.headers['x-api-key'],'test-only')
            self.assertNotIn('test-only',req.content.decode())
            self.assertEqual(body['messages'][0]['content'][1]['source']['type'],'base64')
            self.assertEqual(body['messages'][-1]['content'][0]['tool_use_id'],'tool_one')
            return httpx.Response(200,json={'stop_reason':'end_turn','content':[{'type':'text','text':'확인'}]})
        llm.client=httpx.AsyncClient(transport=httpx.MockTransport(respond),follow_redirects=False)
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'test.png';Image.new('RGB',(8,8),'white').save(p)
            call=ToolCall(id='tool_one',function=Function(name='myscdoc_search',arguments='{"query":"test"}'))
            r=await llm.ask([Message.user('확인',images=[str(p)]),Message.assistant(tool_calls=[call]),Message.tool('record','tool_one','myscdoc_search')])
            self.assertEqual(r.content,'확인')
        await llm.close()
    async def test_cloud_client_cannot_send(self):
        c=CloudClient('https://example.invalid','test-only')
        with patch.object(c._client,'request',side_effect=AssertionError('network called')):
            with self.assertRaises(CloudError):await c.config()
        await c.close()
    async def test_model_redirect_not_followed(self):
        with patch.dict(os.environ,{'ANTHROPIC_API_KEY':'test-only'}):llm=AnthropicNativeLLM(LLMSettings())
        await llm.client.aclose()
        llm.client=httpx.AsyncClient(transport=httpx.MockTransport(lambda req:httpx.Response(302,headers={'Location':'https://example.invalid'})),follow_redirects=False)
        with self.assertRaises(RuntimeError):await llm.ask([Message.user('test')])
        await llm.close()
    def test_shell_does_not_receive_provider_key(self):
        e=scrubbed_env({'ANTHROPIC_API_KEY':'test-only','PATH':'/usr/bin'})
        self.assertNotIn('ANTHROPIC_API_KEY',e)
    def test_screen_actions_still_require_approval(self):
        p=Policy(SentinelSettings(mode='strict',always_ask_tools=['computer_act']))
        result=p.evaluate('computer_act',{},CallAssessment(risk=RiskLevel.MODERATE))
        self.assertEqual(result.decision,Decision.ASK)
    async def test_myscdoc_tool_validates_queries(self):
        result=await MYSCdocSearch().execute(query='')
        self.assertFalse(result.ok)

if __name__=='__main__':unittest.main()
