// Real geometry needs a face's four corners to read each other (subgroup shuffles). Where the driver
// has none (macOS, old drivers), the armour is ray traced instead. MODEL_FORCE_TRACER tries the tracer
// on any machine.
#if defined(GL_KHR_shader_subgroup_shuffle) && defined(GL_KHR_shader_subgroup_ballot) && !defined(MODEL_FORCE_TRACER)
#define MODEL_GEOMETRY
#endif
