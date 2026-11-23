package studio.otherstuff.ambulando.app.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import studio.otherstuff.ambulando.core.surfaces.*
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class GenericNappletWebHostTest {
    @Test fun arbitraryBundleReplayDirtyBusyAndOpaqueSandbox() {
        val i=InstrumentationRegistry.getInstrumentation()
        val id="unknown-"+java.util.UUID.randomUUID().toString()
        val name="binding_"+java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val manifest=buildJsonObject { put("version",2); put("app_id",id); put("title","Unknown"); put("icon","🌱"); put("bindings",buildJsonObject { put(name,buildJsonObject { put("capability","private-json-file"); put("version",1); put("operations",JsonArray(listOf(JsonPrimitive("read"),JsonPrimitive("save")))) }) }) }
        val metadata=buildJsonObject { put("napplet",buildJsonObject { put("version",2); put("app_id",id); put("bindings",buildJsonObject { put(name,buildJsonObject { put("file_id","11111111-1111-4111-8111-111111111111") }) }) }) }
        val declaration="<script type=\"application/json\" id=\"wingman-napplet-manifest\">$manifest</script>"
        val binding=GenericNapplet.parse(declaration,metadata)!!
        val accepted=CopyOnWriteArrayList<String>(); var first=true
        lateinit var host: GenericNappletWebHost
        i.runOnMainSync { host=GenericNappletWebHost.create(i.targetContext,declaration+"""<script>
            const base={type:'wingman-napplet-data',version:2,session:nappletSession,requestId:'busy',operation:'read',binding:'$name'};
            parent.postMessage(base,'*');parent.postMessage(base,'*');parent.postMessage(base,'*');
            parent.postMessage({...base,requestId:'dirty',operation:'dirty',dirty:true,binding:undefined},'*');
            parent.postMessage({type:base.type,version:2,session:nappletSession,requestId:'dirty',operation:'dirty',dirty:true},'*');
            parent.postMessage({type:base.type,version:2,session:nappletSession,requestId:'dirty',operation:'dirty',dirty:false},'*');
            parent.postMessage({...base,requestId:'extra',fileId:'11111111-1111-4111-8111-111111111111'},'*');
            parent.postMessage({...base,requestId:'wrong',session:'wrong'},'*');
            let serial=0;const save=value=>parent.postMessage({...base,requestId:'invalid'+(++serial),operation:'save',baseVersionId:'11111111-1111-4111-8111-111111111111',value},'*');
            save({sparse:[,]});const huge=[];huge.length=4294967295;save({huge});
            save({date:new Date(0)});save({map:new Map([['a',1]])});save({minus:-0});save({number:NaN});save({number:Infinity});
            const extended=[0];extended.extra=true;save({extended});save(JSON.parse('{"constructor":0}'));save({text:'\ud800'});save({text:'a'.repeat(65537)});
            let deep={};for(let n=0;n<33;n++)deep={next:deep};save(deep);save({nodes:Array(49999).fill(null)});
            const large={};for(let n=0;n<17;n++)large['k'+n]='a'.repeat(65536);save(large);
            parent.postMessage({...base,requestId:'valid',operation:'save',baseVersionId:'11111111-1111-4111-8111-111111111111',value:{zero:0,answered:false}},'*');
            addEventListener('message',e=>{if(e.source===parent&&e.data.status==='ready') document.title='reply|'+e.data.value.zero+'|'+e.data.value.answered});
            if(typeof window.nostr!=='undefined') document.title='breach';
        </script>""",binding,accept={ request ->
            if(first) { first=false; false } else { accepted+=GenericNapplet.text(request,"requestId"); true }
        },onStopped={}).getOrThrow() }
        val deadline=System.currentTimeMillis()+15000
        while(System.currentTimeMillis()<deadline && accepted.size<3) Thread.sleep(50)
        assertEquals(listOf("busy","dirty","valid"),accepted.toList())
        assertTrue(host.session.matches(Regex("[a-f0-9]{32}")))
        i.runOnMainSync { host.deliver(buildJsonObject { put("status","ready"); put("value",buildJsonObject { put("zero",0); put("answered",false) }) }) }
        var title=""
        while(System.currentTimeMillis()<deadline && title!="reply|0|false") { i.runOnMainSync { title=host.view.title.orEmpty() }; Thread.sleep(50) }
        assertEquals("reply|0|false",title)
        i.runOnMainSync { assertFalse(host.view.settings.domStorageEnabled); assertTrue(host.view.settings.blockNetworkLoads); assertFalse(host.view.settings.allowFileAccess); host.destroy() }
    }
}
