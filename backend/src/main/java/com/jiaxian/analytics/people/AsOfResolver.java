package com.jiaxian.analytics.people;

import com.jiaxian.analytics.reader.ReaderQueryRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/** 统计日：显式传入就用它，否则取最近一次登录/发帖的日期（同 KOL、读者分析、增长闭环），没有数据时用今天。 */
@Component
public class AsOfResolver {

    private final ReaderQueryRepository readers;
    private final Clock clock;

    public AsOfResolver(ReaderQueryRepository readers, Clock clock) {
        this.readers = readers;
        this.clock = clock;
    }

    public LocalDate resolve(LocalDate asOf) {
        if (asOf != null) {
            return asOf;
        }
        return readers.loginPostSpan().map(ReaderQueryRepository.DateSpan::to).orElse(LocalDate.now(clock));
    }
}
