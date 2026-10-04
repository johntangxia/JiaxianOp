package com.jiaxian.analytics.pv;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class PvConfig {

    /** 统一的时钟 Bean，业务代码取"今天"都走它，测试里可以替换成固定时钟。 */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
