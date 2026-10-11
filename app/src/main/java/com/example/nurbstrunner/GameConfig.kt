package com.example.nurbstrunner

object GameConfig {
    const val VERSION = "1.2.0"

    // track — 5 lanes
    const val LANES = 5
    const val LANE_W = 1.6f
    const val START_SPEED = 14f
    const val ACCEL = 0.35f
    const val GRAVITY = 24f
    const val JUMP_V = 9f
    const val PAD_JUMP_V = 12.5f

    // world
    const val CHECKPOINT_EVERY = 400f
    const val SPAWN_Z = -150f
    const val DESPAWN_Z = 16f
    const val SPAWN_HORIZON = 250f

    // player
    const val START_SHIELD = 3
    const val MAX_SHIELD = 5

    // difficulty
    const val DIFF_RAMP_DIST = 1600f
    const val DRIFT_AFTER = 700f
    const val DRIFT_AMPLITUDE = 0.55f

    // terrain
    const val CHASM_LEN = 5.5f
    const val CHASM_CLEAR_Y = 0.25f

    // path forks
    const val FORK_FIRST = 380f
    const val FORK_ENV_LEN = 35f
    const val FORK_SPREAD = 3.6f
    const val FORK_HALF = 2.45f
    const val CROSS_MAX_ENV = 0.75f
    const val FORK_PREVIEW_DIST = 170f
    const val FORK_FOV_EXTRA = 7f
    const val RISK_ORB_MULT = 1.5f
    const val RISK_CLEAR_BONUS = 60f

    // game feel
    const val STYLE_WINDOW = 3f
    const val STYLE_MAX_MULT = 5
    const val PERFECT_X = 0.28f
    const val SLAM_FALL_V = 15f
    const val SLAM_RADIUS_Z = 3.2f
    const val SLAM_RADIUS_X = 2.4f
    const val WIND_FIT = 3.5f
    const val MILESTONE_STEP = 500f
    const val SPEED_SCORE_K = 0.08f
    const val BOOST_IFRAMES = 0.5f
    const val TENSION_STREAK = 2

    // meta
    const val RUN_HISTORY_MAX = 8

    // simulation
    const val SIM_STEP = 1f / 60f
    const val MAX_CATCHUP_STEPS = 4

    // bend / hill fields
    const val BEND_SPACING = 55f;  const val BEND_STEP = 5.5f;  const val BEND_MAX = 9f
    const val HILL_SPACING = 40f;  const val HILL_STEP = 1.9f;  const val HILL_MAX = 4.2f
    const val AMP_SPACING = 90f;   const val AMP_STEP = 0.8f;   const val AMP_MAX = 1.0f
}
