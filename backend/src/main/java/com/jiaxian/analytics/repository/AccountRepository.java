package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {
}
