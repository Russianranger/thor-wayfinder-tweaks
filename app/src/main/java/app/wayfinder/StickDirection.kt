package app.wayfinder

/** PHYSICAL stick directions. Ordinals are the bits of wfpad's `sd` mask; keep this order. */
enum class StickDirection(val label: String, val isLeft: Boolean) {
    LU("Left stick up", true), LD("Left stick down", true),
    LL("Left stick left", true), LR("Left stick right", true),
    RU("Right stick up", false), RD("Right stick down", false),
    RL("Right stick left", false), RR("Right stick right", false),
}
