package com.jiaxian.analytics.simulator;

/** 模拟员工的博主画像，只影响登录行为：STAR 持续活跃，RISING 近期才冒头，CHURNING 近期突然沉默，DORMANT 长期沉寂，RANDOM 保持原有随机行为。 */
public enum BloggerPersona {
    STAR, RISING, CHURNING, DORMANT, RANDOM
}
