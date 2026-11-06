package com.shilapi.xcertplay.pioneer;

import org.junit.Test;
import static org.junit.Assert.*;

public class PioneerRootHotspotServiceTest {
    private static final String TOKEN="0123456789abcdef0123456789abcdef";
    @Test public void disabledAdaptationOnlyAllowsPreviouslyAuthorizedCleanup() {
        for(String action:new String[]{"PREPARE","PREPARE_LOCAL_ONLY","RESUME_LOCAL_ONLY","START","FIVE_GHZ","FIREWALL","STATUS","STOP"}) {
            assertFalse(PioneerRootHotspotService.hotspotAllowed(action,TOKEN,true,false));
            assertTrue(PioneerRootHotspotService.hotspotAllowed(action,TOKEN,true,true));
        }
        for(String action:new String[]{"STOP_OWNED","RELEASE"}) {
            assertTrue(PioneerRootHotspotService.hotspotAllowed(action,TOKEN,true,false));
            assertFalse(PioneerRootHotspotService.hotspotAllowed(action,TOKEN,false,true));
        }
    }
    @Test public void rpcCannotLaunchWorkerOrInjectAnActionOrLease() {
        for(String action:new String[]{"WORKER","PREPARE; reboot","","start"})
            assertFalse(PioneerRootHotspotService.hotspotAllowed(action,TOKEN,true,true));
        for(String token:new String[]{"",TOKEN+"/../x",TOKEN.toUpperCase(),"abcd"})
            assertFalse(PioneerRootHotspotService.hotspotAllowed("PREPARE",token,true,true));
    }
}
