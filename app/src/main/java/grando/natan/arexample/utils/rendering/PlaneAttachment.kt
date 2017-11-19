package grando.natan.arexample.utils.rendering

import com.google.ar.core.Anchor
import com.google.ar.core.Plane
import com.google.ar.core.Pose

/**
 * This class tracks the attachment of object's Anchor to a Plane. It will construct a pose
 * that will stay on the plane (in Y direction), while still properly tracking the XZ changes
 * from the anchor updates.
 */
class PlaneAttachment(private val mPlane: Plane, val anchor: Anchor) {

    // Allocate temporary storage to avoid multiple allocations per frame.
    private val mPoseTranslation = FloatArray(3)
    private val mPoseRotation = FloatArray(4)

    /*true if*/ val isTracking: Boolean
        get() =
            mPlane.trackingState == Plane.TrackingState.TRACKING && anchor.trackingState == Anchor.TrackingState.TRACKING

    val pose: Pose
        get() {
            val pose = anchor.pose
            pose.getTranslation(mPoseTranslation, 0)
            pose.getRotationQuaternion(mPoseRotation, 0)
            mPoseTranslation[1] = mPlane.centerPose.ty()
            return Pose(mPoseTranslation, mPoseRotation)
        }
}
