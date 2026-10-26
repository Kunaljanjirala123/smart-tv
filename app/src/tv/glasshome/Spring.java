package tv.glasshome;

/**
 * A damped spring for a few values at once, stepped from Choreographer frames. When the target
 * moves mid-flight the values keep their velocity, so holding a D-pad key glides instead of
 * restarting from rest on every press, and a late frame never makes the motion jump.
 */
final class Spring {
    final float[] pos, vel, target;
    private final float stiffness, friction;

    /** {@code dampingRatio} 1 settles with no overshoot; below 1 overshoots a little. */
    Spring(int n, float stiffness, float dampingRatio) {
        pos = new float[n];
        vel = new float[n];
        target = new float[n];
        this.stiffness = stiffness;
        friction = 2f * (float) Math.sqrt(stiffness) * dampingRatio;
    }

    /** Jumps straight to the target. */
    void snap() {
        System.arraycopy(target, 0, pos, 0, pos.length);
        java.util.Arrays.fill(vel, 0f);
    }

    /** Advances {@code dt} seconds; returns true while any value is still moving. */
    boolean step(float dt) {
        // Small fixed sub-steps keep the spring stable and identical at 50, 60 or 30 fps.
        int steps = Math.max(1, (int) Math.ceil(dt / 0.004f));
        float h = dt / steps;
        for (int s = 0; s < steps; s++) {
            for (int i = 0; i < pos.length; i++) {
                vel[i] += (-stiffness * (pos[i] - target[i]) - friction * vel[i]) * h;
                pos[i] += vel[i] * h;
            }
        }
        boolean moving = false;
        for (int i = 0; i < pos.length; i++) {
            if (Math.abs(pos[i] - target[i]) > 0.25f || Math.abs(vel[i]) > 3f) {
                moving = true;
            } else {
                pos[i] = target[i];
                vel[i] = 0f;
            }
        }
        return moving;
    }

    /** Seconds since the previous frame, from Choreographer timestamps, kept to a sane range. */
    static float dt(long now, long last) {
        return last == 0 ? 1f / 60 : Math.max(0f, Math.min((now - last) / 1e9f, 1f / 20));
    }
}
