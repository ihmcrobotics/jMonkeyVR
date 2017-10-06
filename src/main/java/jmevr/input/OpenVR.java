/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmevr.input;

import com.jme3.math.Matrix4f;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import jmevr.app.VRApplication;
import jmevr.util.VRUtil;
import jopenvr.HmdMatrix34_t;
import jopenvr.HmdMatrix44_t;
import jopenvr.JOpenVRLibrary;
import jopenvr.TrackedDevicePose_t;
import jopenvr.VR_IVRCompositor_FnTable;
import jopenvr.VR_IVRSystem_FnTable;

/**
 *
 * @author phr00t
 */
public class OpenVR implements VRAPI {
    
    public enum HMD_TYPE {
        HTC_VIVE, OCULUS_RIFT, OSVR, FOVE, STARVR, GAMEFACE, MORPHEUS, GEARVR, NULL, NONE, OTHER
    }
    
    private static VR_IVRCompositor_FnTable compositorFunctions;
    private static VR_IVRSystem_FnTable vrsystemFunctions;
    
    private static boolean initSuccess = false, flipEyes = false;
    
    private static IntBuffer hmdDisplayFrequency;
    private static TrackedDevicePose_t.ByReference hmdTrackedDevicePoseReference;
    protected static TrackedDevicePose_t[] hmdTrackedDevicePoses;
    
    protected static IntBuffer hmdErrorStore;
    
    private static final Quaternion rotStore = new Quaternion();
    private static final Vector3f posStore = new Vector3f();
    
    private static FloatBuffer tlastVsync;
    public static LongBuffer _tframeCount;
    
    // for debugging latency
    private int frames = 0;    
    
    protected static Matrix4f[] poseMatrices;
    
    private static final Matrix4f hmdPose = Matrix4f.IDENTITY.clone();
    private static Matrix4f hmdProjectionLeftEye;
    private static Matrix4f hmdProjectionRightEye;
    private static Matrix4f hmdPoseLeftEye;
    private static Matrix4f hmdPoseRightEye;
    
    private static Vector3f hmdPoseLeftEyeVec, hmdPoseRightEyeVec, hmdSeatToStand;
    
    private static float vsyncToPhotons;
    private static double timePerFrame, frameCountRun;
    private static long frameCount;
    private static OpenVRInput VRinput;
    
    public OpenVRInput getVRinput() {
        return VRinput;
    }
    
    public VR_IVRSystem_FnTable getVRSystem() {
        return vrsystemFunctions;
    }
    
    public VR_IVRCompositor_FnTable getCompositor() {
        return compositorFunctions;
    }
    
    public String getName() {
        return "OpenVR";
    }
    
    private static long latencyWaitTime = 0;
    
    /*
        do not use. set via preconfigure routine in VRApplication
    */
    public void _setFlipEyes(boolean set) {
        flipEyes = set;
    }
    
    private boolean enableDebugLatency = false;
    public void printLatencyInfoToConsole(boolean set) {
        enableDebugLatency = set;
    }

    public int getDisplayFrequency() {
        if( hmdDisplayFrequency == null ) return 0;
        return hmdDisplayFrequency.get(0);
    }
    
    public boolean initialize() {
        hmdErrorStore = IntBuffer.allocate(1);
        vrsystemFunctions = null;
        JOpenVRLibrary.VR_InitInternal(hmdErrorStore, JOpenVRLibrary.EVRApplicationType.EVRApplicationType_VRApplication_Scene);
        if( hmdErrorStore.get(0) == 0 ) {
            // ok, try and get the vrsystem pointer..
            vrsystemFunctions = new VR_IVRSystem_FnTable(JOpenVRLibrary.VR_GetGenericInterface(JOpenVRLibrary.IVRSystem_Version, hmdErrorStore));
        }
        if( vrsystemFunctions == null || hmdErrorStore.get(0) != 0 ) {
            System.out.println("OpenVR Initialize Result: " + JOpenVRLibrary.VR_GetVRInitErrorAsEnglishDescription(hmdErrorStore.get(0)).getString(0));
            return false;
        } else {
            System.out.println("OpenVR initialized & VR connected.");
            
            vrsystemFunctions.setAutoSynch(false);
            vrsystemFunctions.read();
            
            tlastVsync = FloatBuffer.allocate(1);
            _tframeCount = LongBuffer.allocate(1);
            
            hmdDisplayFrequency = IntBuffer.allocate(1);
            hmdDisplayFrequency.put( (int) JOpenVRLibrary.ETrackedDeviceProperty.ETrackedDeviceProperty_Prop_DisplayFrequency_Float);
            hmdTrackedDevicePoseReference = new TrackedDevicePose_t.ByReference();
            hmdTrackedDevicePoses = (TrackedDevicePose_t[])hmdTrackedDevicePoseReference.toArray(JOpenVRLibrary.k_unMaxTrackedDeviceCount);
            poseMatrices = new Matrix4f[JOpenVRLibrary.k_unMaxTrackedDeviceCount];
            for(int i=0;i<poseMatrices.length;i++) poseMatrices[i] = new Matrix4f();

            timePerFrame = 1.0 / hmdDisplayFrequency.get(0);
            
            // disable all this stuff which kills performance
            hmdTrackedDevicePoseReference.setAutoRead(false);
            hmdTrackedDevicePoseReference.setAutoWrite(false);
            hmdTrackedDevicePoseReference.setAutoSynch(false);
            for(int i=0;i<JOpenVRLibrary.k_unMaxTrackedDeviceCount;i++) {
                hmdTrackedDevicePoses[i].setAutoRead(false);
                hmdTrackedDevicePoses[i].setAutoWrite(false);
                hmdTrackedDevicePoses[i].setAutoSynch(false);
            }
            
            // init controllers for the first time
            VRinput = new OpenVRInput();
            VRinput.init();
            VRApplication.getVRinput()._updateConnectedControllers();
            
            // init bounds & chaperone info
            VRBounds.init();
            
            initSuccess = true;
            return true;
        }
    }
    
    public boolean initVRCompositor(boolean allowed) {
        hmdErrorStore.put(0, 0); // clear the error store
        if( allowed && vrsystemFunctions != null ) {
            compositorFunctions = new VR_IVRCompositor_FnTable(JOpenVRLibrary.VR_GetGenericInterface(JOpenVRLibrary.IVRCompositor_Version, hmdErrorStore));
            if(compositorFunctions != null && hmdErrorStore.get(0) == 0 ){                
                System.out.println("OpenVR Compositor initialized OK!");
                compositorFunctions.setAutoSynch(false);
                compositorFunctions.read();
                if( VRApplication.isSeatedExperience() ) {                    
                    compositorFunctions.SetTrackingSpace.apply(JOpenVRLibrary.ETrackingUniverseOrigin.ETrackingUniverseOrigin_TrackingUniverseSeated);
                } else {
                    compositorFunctions.SetTrackingSpace.apply(JOpenVRLibrary.ETrackingUniverseOrigin.ETrackingUniverseOrigin_TrackingUniverseStanding);                
                }
            } else {
                System.out.println("OpenVR Compositor error: " + hmdErrorStore.get(0));
                compositorFunctions = null;
            }
        }
        if( compositorFunctions == null ) {
            System.out.println("Skipping VR Compositor...");
            if( vrsystemFunctions != null ) {
                vsyncToPhotons = vrsystemFunctions.GetFloatTrackedDeviceProperty.apply(JOpenVRLibrary.k_unTrackedDeviceIndex_Hmd, JOpenVRLibrary.ETrackedDeviceProperty.ETrackedDeviceProperty_Prop_SecondsFromVsyncToPhotons_Float, hmdErrorStore);
            } else {
                vsyncToPhotons = 0f;
            }
        }
        return compositorFunctions != null;
    }

    public void destroy() {
        JOpenVRLibrary.VR_ShutdownInternal();
    }

    public boolean isInitialized() {
        return initSuccess;
    }

    public void reset() {
        if( vrsystemFunctions == null ) return;
        vrsystemFunctions.ResetSeatedZeroPose.apply();
        hmdSeatToStand = null;
    }

    public void getRenderSize(Vector2f store) {
        if( vrsystemFunctions == null ) {
            // 1344x1512
            store.x = 1344f;
            store.y = 1512f;
        } else {
            IntBuffer x = IntBuffer.allocate(1);
            IntBuffer y = IntBuffer.allocate(1);
            vrsystemFunctions.GetRecommendedRenderTargetSize.apply(x, y);
            store.x = x.get(0);
            store.y = y.get(0);
        }
    }
    
    public float getFOV(int dir) {
        float val = 0f;
        if( vrsystemFunctions != null ) {      
            val = vrsystemFunctions.GetFloatTrackedDeviceProperty.apply(JOpenVRLibrary.k_unTrackedDeviceIndex_Hmd, dir, hmdErrorStore);
        }
        // verification of number
        if( val == 0f ) {
            return 55f;
        } else if( val <= 10f ) {
            // most likely a radian number
            return val * 57.2957795f;
        }
        return val;
    }

    public float getInterpupillaryDistance() {
        if( vrsystemFunctions == null ) return 0.065f;
        return vrsystemFunctions.GetFloatTrackedDeviceProperty.apply(JOpenVRLibrary.k_unTrackedDeviceIndex_Hmd, JOpenVRLibrary.ETrackedDeviceProperty.ETrackedDeviceProperty_Prop_UserIpdMeters_Float, hmdErrorStore);
    }
    
    public Quaternion getOrientation() {
        VRUtil.convertMatrix4toQuat(hmdPose, rotStore);
        return rotStore;
    }

    public Vector3f getPosition() {
        // the hmdPose comes in rotated funny, fix that here
        hmdPose.toTranslationVector(posStore);
        posStore.x = -posStore.x;
        posStore.z = -posStore.z;
        return posStore;
    }
    
    @Override
    public void getPositionAndOrientation(Vector3f storePos, Quaternion storeRot) {
        hmdPose.toTranslationVector(storePos);
        storePos.x = -storePos.x;
        storePos.z = -storePos.z;
        storeRot.set(getOrientation());
    }    
    
    @Override
    public void updatePose(){
        if(vrsystemFunctions == null) return;
        if(compositorFunctions != null) {
           compositorFunctions.WaitGetPoses.apply(hmdTrackedDevicePoseReference, JOpenVRLibrary.k_unMaxTrackedDeviceCount, null, 0);
        } else {
            // wait
            if( latencyWaitTime > 0 ) VRUtil.sleepNanos(latencyWaitTime);
                        
            vrsystemFunctions.GetTimeSinceLastVsync.apply(tlastVsync, _tframeCount);
            float fSecondsUntilPhotons = (float)timePerFrame - tlastVsync.get(0) + vsyncToPhotons;
            
            if( enableDebugLatency ) {
                if( frames == 10 ) {
                    System.out.println("Waited (nanos): " + Long.toString(latencyWaitTime));
                    System.out.println("Predict ahead time: " + Float.toString(fSecondsUntilPhotons));
                }
                frames = (frames + 1) % 60;            
            }            
            
            // handle skipping frame stuff
            long nowCount = _tframeCount.get(0);
            if( nowCount - frameCount > 1 ) {
                // skipped a frame!
                if( enableDebugLatency ) System.out.println("Frame skipped!");
                frameCountRun = 0;
                if( latencyWaitTime > 0 ) {
                    latencyWaitTime -= TimeUnit.MILLISECONDS.toNanos(1);
                    if( latencyWaitTime < 0 ) latencyWaitTime = 0;
                }
            } else if( latencyWaitTime < timePerFrame * 1000000000.0 ) {
                // didn't skip a frame, lets try waiting longer to improve latency
                frameCountRun++;
                latencyWaitTime += Math.round(Math.pow(frameCountRun / 10.0, 2.0));
            }

            frameCount = nowCount;
            
            vrsystemFunctions.GetDeviceToAbsoluteTrackingPose.apply(
                    VRApplication.isSeatedExperience()?JOpenVRLibrary.ETrackingUniverseOrigin.ETrackingUniverseOrigin_TrackingUniverseSeated:
                                                       JOpenVRLibrary.ETrackingUniverseOrigin.ETrackingUniverseOrigin_TrackingUniverseStanding,
                    fSecondsUntilPhotons, hmdTrackedDevicePoseReference, JOpenVRLibrary.k_unMaxTrackedDeviceCount);   
        }
        
        // deal with controllers being plugged in and out
        // causing an invalid memory crash... skipping for now
        /*boolean hasEvent = false;
        while( JOpenVRLibrary.VR_IVRSystem_PollNextEvent(OpenVR.getVRSystemInstance(), tempEvent) != 0 ) {
            // wait until the events are clear..
            hasEvent = true;
        }
        if( hasEvent ) {
            // an event probably changed controller state
            VRInput._updateConnectedControllers();
        }*/
        //update controllers pose information
        VRApplication.getVRinput()._updateControllerStates();
                
        // read pose data from native
        for (int nDevice = 0; nDevice < JOpenVRLibrary.k_unMaxTrackedDeviceCount; ++nDevice ){
            hmdTrackedDevicePoses[nDevice].readField("bPoseIsValid");
            if( hmdTrackedDevicePoses[nDevice].bPoseIsValid != 0 ){
                hmdTrackedDevicePoses[nDevice].readField("mDeviceToAbsoluteTracking");
                VRUtil.convertSteamVRMatrix3ToMatrix4f(hmdTrackedDevicePoses[nDevice].mDeviceToAbsoluteTracking, poseMatrices[nDevice]);
            }            
        }
        
        if ( hmdTrackedDevicePoses[JOpenVRLibrary.k_unTrackedDeviceIndex_Hmd].bPoseIsValid != 0 ){
            hmdPose.set(poseMatrices[JOpenVRLibrary.k_unTrackedDeviceIndex_Hmd]);
        } else {
            hmdPose.set(Matrix4f.IDENTITY);
        }
    }

    public Matrix4f getHMDMatrixProjectionLeftEye(Camera cam){
        if( hmdProjectionLeftEye != null ) {
            return hmdProjectionLeftEye;
        } else if(vrsystemFunctions == null){
            return cam.getProjectionMatrix();
        } else {
            HmdMatrix44_t mat = vrsystemFunctions.GetProjectionMatrix.apply(JOpenVRLibrary.EVREye.EVREye_Eye_Left, cam.getFrustumNear(), cam.getFrustumFar(), JOpenVRLibrary.EGraphicsAPIConvention.EGraphicsAPIConvention_API_OpenGL);
            hmdProjectionLeftEye = new Matrix4f();
            VRUtil.convertSteamVRMatrix4ToMatrix4f(mat, hmdProjectionLeftEye);
            return hmdProjectionLeftEye;
        }
    }
        
    public Matrix4f getHMDMatrixProjectionRightEye(Camera cam){
        if( hmdProjectionRightEye != null ) {
            return hmdProjectionRightEye;
        } else if(vrsystemFunctions == null){
            return cam.getProjectionMatrix();
        } else {
            HmdMatrix44_t mat = vrsystemFunctions.GetProjectionMatrix.apply(JOpenVRLibrary.EVREye.EVREye_Eye_Right, cam.getFrustumNear(), cam.getFrustumFar(), JOpenVRLibrary.EGraphicsAPIConvention.EGraphicsAPIConvention_API_OpenGL);
            hmdProjectionRightEye = new Matrix4f();
            VRUtil.convertSteamVRMatrix4ToMatrix4f(mat, hmdProjectionRightEye);
            return hmdProjectionRightEye;
        }
    }
    
    public Vector3f getHMDVectorPoseLeftEye() {
        if( hmdPoseLeftEyeVec == null ) {
            hmdPoseLeftEyeVec = getHMDMatrixPoseLeftEye().toTranslationVector();
            // set default IPD if none or broken
            if( hmdPoseLeftEyeVec.x <= 0.080f * -0.5f || hmdPoseLeftEyeVec.x >= 0.040f * -0.5f ) {
                hmdPoseLeftEyeVec.x = 0.065f * -0.5f;
            }
            if( flipEyes == false ) hmdPoseLeftEyeVec.x *= -1f; // it seems these need flipping
        }
        return hmdPoseLeftEyeVec;
    }
    
    public Vector3f getHMDVectorPoseRightEye() {
        if( hmdPoseRightEyeVec == null ) {
            hmdPoseRightEyeVec = getHMDMatrixPoseRightEye().toTranslationVector();
            // set default IPD if none or broken
            if( hmdPoseRightEyeVec.x >= 0.080f * 0.5f || hmdPoseRightEyeVec.x <= 0.040f * 0.5f ) {
                hmdPoseRightEyeVec.x = 0.065f * 0.5f;
            }
            if( flipEyes == false ) hmdPoseRightEyeVec.x *= -1f; // it seems these need flipping
        }
        return hmdPoseRightEyeVec;
    }
    
    public Vector3f getSeatedToAbsolutePosition() {
        if( VRApplication.isSeatedExperience() == false ) return Vector3f.ZERO;
        if( hmdSeatToStand == null ) {
            hmdSeatToStand = new Vector3f();
            HmdMatrix34_t mat = vrsystemFunctions.GetSeatedZeroPoseToStandingAbsoluteTrackingPose.apply();
            Matrix4f tempmat = new Matrix4f();
            VRUtil.convertSteamVRMatrix3ToMatrix4f(mat, tempmat);
            tempmat.toTranslationVector(hmdSeatToStand);
        }
        return hmdSeatToStand;
    }
    
    public Matrix4f getHMDMatrixPoseLeftEye(){
        if( hmdPoseLeftEye != null ) {
            return hmdPoseLeftEye;
        } else if(vrsystemFunctions == null) {
            return Matrix4f.IDENTITY;
        } else {
            HmdMatrix34_t mat = vrsystemFunctions.GetEyeToHeadTransform.apply(JOpenVRLibrary.EVREye.EVREye_Eye_Left);
            hmdPoseLeftEye = new Matrix4f();
            return VRUtil.convertSteamVRMatrix3ToMatrix4f(mat, hmdPoseLeftEye);
        }
    }
    
    public HMD_TYPE getType() {
        if( vrsystemFunctions != null ) {      
            Pointer str1 = new Memory(128);
            Pointer str2 = new Memory(128);
            String completeName = "";
            vrsystemFunctions.GetStringTrackedDeviceProperty.apply(JOpenVRLibrary.k_unTrackedDeviceIndex_Hmd,
                                                                   JOpenVRLibrary.ETrackedDeviceProperty.ETrackedDeviceProperty_Prop_ManufacturerName_String,
                                                                   str1, 128, hmdErrorStore);
            if( hmdErrorStore.get(0) == 0 ) completeName += str1.getString(0);
            vrsystemFunctions.GetStringTrackedDeviceProperty.apply(JOpenVRLibrary.k_unTrackedDeviceIndex_Hmd,
                                                                   JOpenVRLibrary.ETrackedDeviceProperty.ETrackedDeviceProperty_Prop_ModelNumber_String,
                                                                   str2, 128, hmdErrorStore);
            if( hmdErrorStore.get(0) == 0 ) completeName += " " + str2.getString(0);
            if( completeName.length() > 0 ) {
                completeName = completeName.toLowerCase(Locale.ENGLISH).trim();
                if( completeName.contains("htc") || completeName.contains("vive") ) {
                    return HMD_TYPE.HTC_VIVE;
                } else if( completeName.contains("osvr") ) {
                    return HMD_TYPE.OSVR;
                } else if( completeName.contains("oculus") || completeName.contains("rift") ||
                           completeName.contains("dk1") || completeName.contains("dk2") || completeName.contains("cv1") ) {
                    return HMD_TYPE.OCULUS_RIFT;
                } else if( completeName.contains("fove") ) {
                    return HMD_TYPE.FOVE;
                } else if( completeName.contains("game") && completeName.contains("face") ) {
                    return HMD_TYPE.GAMEFACE;
                } else if( completeName.contains("morpheus") ) {
                    return HMD_TYPE.MORPHEUS;
                } else if( completeName.contains("gear") ) {
                    return HMD_TYPE.GEARVR;
                } else if( completeName.contains("star") ) {
                    return HMD_TYPE.STARVR;
                } else if( completeName.contains("null") ) {
                    return HMD_TYPE.NULL;
                }
            }
        } else return HMD_TYPE.NONE;
        return HMD_TYPE.OTHER;
    }
    
    public Matrix4f getHMDMatrixPoseRightEye(){
        if( hmdPoseRightEye != null ) {
            return hmdPoseRightEye;
        } else if(vrsystemFunctions == null) {
            return Matrix4f.IDENTITY;
        } else {
            HmdMatrix34_t mat = vrsystemFunctions.GetEyeToHeadTransform.apply(JOpenVRLibrary.EVREye.EVREye_Eye_Right);
            hmdPoseRightEye = new Matrix4f();
            return VRUtil.convertSteamVRMatrix3ToMatrix4f(mat, hmdPoseRightEye);
        }
    }
    
}
