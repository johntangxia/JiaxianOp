package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {
    List<Comment> findByParentCommentId(Long parentCommentId);

    // 自引用外键（parent_comment_id -> comment.id）不能直接 deleteAll()：JPA 按 findAll() 的顺序
    // （通常主键升序，也就是"原评论"排在"回复"前面）逐条删，会在删到还被回复引用的原评论时报外键
    // 约束错误。先用这条批量 DELETE 把所有"是回复"的行清掉，再对剩下的（此时都是无人引用的顶层
    // 评论）调用普通的 deleteAll()，就不会有顺序问题。
    //
    // flushAutomatically = true：这是一条 JPQL 批量 DELETE，默认不会先 flush 持久化上下文。
    // clearPreviousSimulationData() 在调用这个方法之前先调用了 engagementLogRepository.deleteAll()，
    // 那是逐条实体删除，删除动作只是排进了持久化上下文，还没真正下发到数据库。如果这里不强制
    // flush，Hibernate 对批量 JPQL 的“智能 flush”只会考虑 comment 表本身，不会把还未落盘的
    // engagement_log 删除一并刷出去，导致这条批量 DELETE 撞到 engagement_log.comment_id 外键。
    // clearAutomatically = true：flush 之后顺便清空持久化上下文，避免残留的托管实体影响
    // clearPreviousSimulationData() 里后续那些各自独立 findAll() 的 deleteAll() 调用（这里没有
    // 谁会在此之后重新读取之前已加载的 Comment/EngagementLog 实体，清空是安全的）。
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM Comment c WHERE c.parentCommentId IS NOT NULL")
    void deleteReplies();
}
