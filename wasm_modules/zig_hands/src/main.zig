const std = @import("std");

// 0.001ms Bezier-curved humanized touch coordinate generator
export fn compute_curved_step(start_x: f32, start_y: f32, end_x: f32, end_y: f32, t: f32, out_xy: [*]f32) void {
    const ctrl_x = (start_x + end_x) * 0.5 + 18.0;
    const ctrl_y = (start_y + end_y) * 0.5 - 24.0;
    const inv_t = 1.0 - t;
    out_xy[0] = (inv_t * inv_t * start_x) + (2.0 * inv_t * t * ctrl_x) + (t * t * end_x);
    out_xy[1] = (inv_t * inv_t * start_y) + (2.0 * inv_t * t * ctrl_y) + (t * t * end_y);
}
