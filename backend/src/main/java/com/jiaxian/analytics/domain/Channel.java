package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "channel")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Channel {
    /** 综合频道：关注、推荐、热点、视频、直播——只做分发，没有自己的内容。 */
    public static final String CATEGORY_COMPOSITE = "综合频道";
    /** 领域频道：内容的归属地，同时也有自己的入口和信息流。 */
    public static final String CATEGORY_DOMAIN = "领域频道";
    /** 种子里已经移除的旧频道，仍被旧数据引用时的临时类别，下次历史回填会清掉。 */
    public static final String CATEGORY_RETIRED = "已停用";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String category;
}
