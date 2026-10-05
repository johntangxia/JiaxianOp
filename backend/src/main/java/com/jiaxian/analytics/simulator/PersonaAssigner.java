package com.jiaxian.analytics.simulator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 给员工序号分配画像：3% STAR、2% RISING、3% CHURNING、5% DORMANT，其余 RANDOM（各比例 round 取整，每类至少 2 人，
 * 这样几十人的小规模测试也能覆盖所有画像；员工太少放不下时按比例截断，总数始终等于员工数）。
 * 用独立的随机流（种子异或一个常量）打乱，不扰动回填的主随机序列；相同的员工数和种子得到相同的结果，
 * 所以画像不用落库，测试用同样的函数就能算出每个员工的画像。
 */
public final class PersonaAssigner {

    static final double STAR_RATIO = 0.03;
    static final double RISING_RATIO = 0.02;
    static final double CHURNING_RATIO = 0.03;
    static final double DORMANT_RATIO = 0.05;
    static final int MIN_PER_PERSONA = 2;
    private static final long SHUFFLE_SALT = 0x5DEECE66DL;

    private PersonaAssigner() {
    }

    /** 返回长度为 employeeCount 的列表，第 i 个元素是按创建顺序的第 i 个员工的画像。 */
    public static List<BloggerPersona> assign(int employeeCount, long seed) {
        List<BloggerPersona> personas = new ArrayList<>();
        add(personas, BloggerPersona.STAR, share(employeeCount, STAR_RATIO), employeeCount);
        add(personas, BloggerPersona.RISING, share(employeeCount, RISING_RATIO), employeeCount);
        add(personas, BloggerPersona.CHURNING, share(employeeCount, CHURNING_RATIO), employeeCount);
        add(personas, BloggerPersona.DORMANT, share(employeeCount, DORMANT_RATIO), employeeCount);
        add(personas, BloggerPersona.RANDOM, employeeCount - personas.size(), employeeCount);
        Collections.shuffle(personas, new Random(seed ^ SHUFFLE_SALT));
        return personas;
    }

    private static long share(int employeeCount, double ratio) {
        return Math.max(MIN_PER_PERSONA, Math.round(employeeCount * ratio));
    }

    private static void add(List<BloggerPersona> personas, BloggerPersona persona, long count, int limit) {
        for (long i = 0; i < count && personas.size() < limit; i++) {
            personas.add(persona);
        }
    }
}
