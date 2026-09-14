package com.ai;

import com.ai.common.PageResult;
import com.ai.session.dto.SessionVO;
import com.ai.session.service.ChatSessionService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AiAgentApplicationTests {

    @Resource
    private ChatSessionService service;


    @Test
    void contextLoads() {

        PageResult<SessionVO> list = service.list(1L, 1, 5);
        System.out.println(list.records());
    }

}
