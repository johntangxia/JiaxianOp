package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ChannelSeedIT {

    @Autowired private ChannelRepository channelRepository;

    private List<String> names(String category) {
        return channelRepository.findByCategory(category).stream().map(Channel::getName).toList();
    }

    @Test
    void compositeChannelsAreTheFiveDistributionEntries() {
        assertThat(names(Channel.CATEGORY_COMPOSITE)).containsExactlyInAnyOrder("关注", "推荐", "热点", "视频", "直播");
    }

    @Test
    void domainChannelsAreTheTwentyThreeContentChannels() {
        assertThat(names(Channel.CATEGORY_DOMAIN)).hasSize(23).contains(
            "AI学习", "终端", "智能汽车", "半导体", "无线", "云核", "AI研发", "自动驾驶网络ADN", "交付服务",
            "服务软件", "华为云", "光", "数通", "数据存储", "计算", "知识产权", "制造", "供应链", "战略", "能源",
            "质量", "海外研究所", "2012");
    }

    @Test
    void legacySeedCategoriesAndChannelsAreGone() {
        assertThat(names("专业领域")).isEmpty();
        // 旧库里如果还有内容引用它们，就会被停用而不是删除；它们不能出现在综合/领域频道里。
        assertThat(names(Channel.CATEGORY_COMPOSITE)).doesNotContain("移动无线", "固定网络", "IT计算");
        assertThat(names(Channel.CATEGORY_DOMAIN)).doesNotContain("移动无线", "固定网络", "IT计算");
        assertThat(channelRepository.findAll()).filteredOn(c -> List.of("移动无线", "固定网络", "IT计算").contains(c.getName()))
            .allMatch(c -> Channel.CATEGORY_RETIRED.equals(c.getCategory()));
    }
}
