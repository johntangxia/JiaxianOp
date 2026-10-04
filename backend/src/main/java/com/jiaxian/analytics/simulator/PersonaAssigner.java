package com.jiaxian.analytics.simulator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 给员工序号分配画像：10% STAR、10% RISING、10% CHURNING、20% DORMANT，其余 RANDOM（各比例 round 取整）。
 * 用独立的随机流（种子异或一个常量）打乱，不扰动回填的主随机序列；相同的员工数和种子得到相同的结果，
 * 所以画像不用落库，测试用同样的函数就能算出每个员工的画像。
 */
public final class PersonaAssigner {

    private static final long SHUFFLE_SALT = 0x5DEECE66DL;

    private PersonaAssigner() {
    }

    /** 返回长度为 employeeCount 的列表，第 i 个元素是按创建顺序的第 i 个员工的画像。 */
    public static List<BloggerPersona> assign(int employeeCount, long seed) {
        List<BloggerPersona> personas = new ArrayList<>();
        add(personas, BloggerPersona.STAR, Math.round(employeeCount * 0.10));
        add(personas, BloggerPersona.RISING, Math.round(employeeCount * 0.10));
        add(personas, BloggerPersona.CHURNING, Math.round(employeeCount * 0.10));
        add(personas, BloggerPersona.DORMANT, Math.round(employeeCount * 0.20));
        add(personas, BloggerPersona.RANDOM, employeeCount - personas.size());
        Collections.shuffle(personas, new Random(seed ^ SHUFFLE_SALT));
        return personas;
    }

    private static void add(List<BloggerPersona> personas, BloggerPersona persona, long count) {
        for (long i = 0; i < count; i++) {
            personas.add(persona);
        }
    }
}
