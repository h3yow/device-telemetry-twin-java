package com.github.h3yow.devicetelemetrytwinjava.rule;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则服务：规则以 (ruleId, revision) 不可变版本链形式存储。
 * 任何 register/update 都只追加新 revision，绝不就地修改，因此：
 * 告警事件上记录的 revision 永远指向当时生效的那份规则，历史判定可复现。
 */
@Service
public class RuleService {

    private final ConcurrentHashMap<String, List<AlertRule>> revisions = new ConcurrentHashMap<>();

    public AlertRule register(String id, String deviceId, String metric,
                              ComparisonOp op, double threshold, long suppressMillis) {
        if (revisions.containsKey(id)) {
            throw new IllegalArgumentException("规则已存在，请走更新接口: " + id);
        }
        AlertRule rule = new AlertRule(id, 1, deviceId, metric, op, threshold, suppressMillis, true);
        List<AlertRule> chain = new ArrayList<>();
        chain.add(rule);
        revisions.put(id, chain);
        return rule;
    }

    /**
     * 基于当前 revision 追加新版本；任一参数传 null 表示保持原值。
     */
    public AlertRule update(String id, ComparisonOp op, Double threshold,
                            Long suppressMillis, Boolean enabled) {
        List<AlertRule> chain = revisions.get(id);
        if (chain == null) {
            throw new IllegalArgumentException("规则不存在: " + id);
        }
        final AlertRule next;
        synchronized (chain) {
            AlertRule cur = chain.get(chain.size() - 1);
            next = new AlertRule(cur.id(), cur.revision() + 1, cur.deviceId(), cur.metric(),
                    op != null ? op : cur.op(),
                    threshold != null ? threshold : cur.threshold(),
                    suppressMillis != null ? suppressMillis : cur.suppressMillis(),
                    enabled != null ? enabled : cur.enabled());
            chain.add(next);
        }
        return next;
    }

    public Optional<AlertRule> current(String ruleId) {
        List<AlertRule> chain = revisions.get(ruleId);
        if (chain == null || chain.isEmpty()) {
            return Optional.empty();
        }
        synchronized (chain) {
            return Optional.of(chain.get(chain.size() - 1));
        }
    }

    public List<AlertRule> rulesForDevice(String deviceId) {
        List<AlertRule> result = new ArrayList<>();
        revisions.values().forEach(chain -> {
            synchronized (chain) {
                if (!chain.isEmpty()) {
                    AlertRule cur = chain.get(chain.size() - 1);
                    if (cur.deviceId().equals(deviceId)) {
                        result.add(cur);
                    }
                }
            }
        });
        result.sort(Comparator.comparing(AlertRule::id));
        return result;
    }

    public Optional<AlertRule> getRevision(String ruleId, int revision) {
        List<AlertRule> chain = revisions.get(ruleId);
        if (chain == null) {
            return Optional.empty();
        }
        synchronized (chain) {
            return chain.stream().filter(r -> r.revision() == revision).findFirst();
        }
    }
}
