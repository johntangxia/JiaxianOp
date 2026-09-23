package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.Employee;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {
}
