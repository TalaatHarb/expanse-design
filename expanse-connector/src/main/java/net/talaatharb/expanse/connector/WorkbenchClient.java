package net.talaatharb.expanse.connector;

import net.talaatharb.expanse.api.WorkbenchApi;
import org.springframework.cloud.openfeign.FeignClient;

@FeignClient(name = "workbench", url = "${expanse.server-url:http://127.0.0.1:8765}")
public interface WorkbenchClient extends WorkbenchApi {
}
