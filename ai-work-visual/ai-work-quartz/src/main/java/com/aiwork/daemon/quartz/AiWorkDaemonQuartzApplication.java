package com.aiwork.daemon.quartz;

import com.aiwork.common.feign.annotation.EnableAiWorkFeignClients;
import com.aiwork.common.security.annotation.EnableAiWorkResourceServer;
import com.aiwork.common.swagger.annotation.EnableOpenApi;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * @author frwcloud
 * @date 2019/01/23 定时任务模块
 */
@EnableOpenApi("job")
@EnableAiWorkFeignClients
@EnableAiWorkResourceServer
@EnableDiscoveryClient
@SpringBootApplication
public class AiWorkDaemonQuartzApplication {

	public static void main(String[] args) {
		SpringApplication.run(AiWorkDaemonQuartzApplication.class, args);
	}

}
