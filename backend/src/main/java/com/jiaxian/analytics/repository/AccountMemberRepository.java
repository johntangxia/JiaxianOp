package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.AccountMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccountMemberRepository extends JpaRepository<AccountMember, Long> {
    List<AccountMember> findByAccountId(Long accountId);
}
