package com.jiaxian.analytics.dimension;

import org.springframework.stereotype.Service;

import java.util.List;

/** 校验参数后转给仓库。 */
@Service
public class DimensionService {

    static final int MAX_LIMIT = 100;

    private final DimensionRepository repository;

    public DimensionService(DimensionRepository repository) {
        this.repository = repository;
    }

    public List<ChannelOption> channels() {
        return repository.channels();
    }

    public List<AccountOption> accounts(String keyword, int limit) {
        requireValidLimit(limit);
        return repository.accounts(keyword, limit);
    }

    public List<EmployeeOption> employees(String keyword, int limit) {
        requireValidLimit(limit);
        return repository.employees(keyword, limit);
    }

    public PvRange pvRange() {
        return repository.pvRange();
    }

    private static void requireValidLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidDimensionQueryException("limit must be between 1 and " + MAX_LIMIT);
        }
    }
}
