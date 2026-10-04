package com.jiaxian.analytics.kol;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 建议文案库：校验、冲突检测、增删改查；以及给名单查询用的一次性查找器。 */
@Service
public class PlaybookService {

    static final int MAX_TEXT_LENGTH = 1000;

    private final PlaybookRepository repository;

    public PlaybookService(PlaybookRepository repository) {
        this.repository = repository;
    }

    public List<PlaybookEntry> list() {
        return repository.list();
    }

    public PlaybookLookup lookup() {
        return new PlaybookLookup(repository.list());
    }

    @Transactional
    public PlaybookEntry create(PlaybookRequest request) {
        validate(request);
        if (repository.findByKey(request.segmentLabel(), request.alertStatus()).isPresent()) {
            throw conflict(request);
        }
        repository.insert(request.segmentLabel(), request.alertStatus(), request.recommendationText().trim());
        return repository.findByKey(request.segmentLabel(), request.alertStatus()).orElseThrow();
    }

    @Transactional
    public PlaybookEntry update(long id, PlaybookRequest request) {
        validate(request);
        repository.find(id).orElseThrow(() -> new PlaybookNotFoundException(id));
        repository.findByKey(request.segmentLabel(), request.alertStatus())
            .filter(other -> other.id() != id)
            .ifPresent(other -> {
                throw conflict(request);
            });
        repository.update(id, request.segmentLabel(), request.alertStatus(), request.recommendationText().trim());
        return repository.find(id).orElseThrow();
    }

    @Transactional
    public void delete(long id) {
        if (repository.delete(id) == 0) {
            throw new PlaybookNotFoundException(id);
        }
    }

    private static PlaybookConflictException conflict(PlaybookRequest request) {
        return new PlaybookConflictException("A playbook entry for " + request.segmentLabel()
            + " / " + request.alertStatus() + " already exists");
    }

    private static void validate(PlaybookRequest request) {
        if (request.segmentLabel() == null) {
            throw new InvalidKolQueryException("segmentLabel is required");
        }
        if (request.alertStatus() == null) {
            throw new InvalidKolQueryException("alertStatus is required");
        }
        String text = request.recommendationText();
        if (text == null || text.isBlank()) {
            throw new InvalidKolQueryException("recommendationText must not be blank");
        }
        if (text.trim().length() > MAX_TEXT_LENGTH) {
            throw new InvalidKolQueryException("recommendationText must be at most " + MAX_TEXT_LENGTH + " characters");
        }
    }
}
