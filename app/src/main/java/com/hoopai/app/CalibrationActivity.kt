package com.hoopai.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class CalibrationActivity:ComponentActivity(){
 private lateinit var preview:PreviewView
 private lateinit var overlay:WorkoutOverlayView
 private lateinit var title:TextView
 private lateinit var hint:TextView
 private lateinit var action:Button
 private var pose:List<NormalizedLandmark> = emptyList()
 private var ball:RectF?=null
 private var frameW=1f; private var frameH=1f
 private var step=0
 private val executor=Executors.newSingleThreadExecutor()
 private var poseLandmarker:PoseLandmarker?=null
 private var detector:ObjectDetector?=null
 private var category="finishing"; private var drill="Right Layup"

 override fun onCreate(b:Bundle?){
  super.onCreate(b)
  category=intent.getStringExtra("category")?:"finishing"
  drill=intent.getStringExtra("drill")?:"Right Layup"
  buildUi()
  if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) startCamera()
  else ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.CAMERA),90)
 }
 private fun buildUi(){
  val root=FrameLayout(this)
  preview=PreviewView(this); overlay=WorkoutOverlayView(this)
  root.addView(preview,FrameLayout.LayoutParams(-1,-1));root.addView(overlay,FrameLayout.LayoutParams(-1,-1))
  val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(22,22,22,18);setBackgroundColor(Color.argb(215,5,15,25))}
  title=TextView(this).apply{text="🧠 کالیبراسیون HoopAI";textSize=23f;setTextColor(Color.WHITE)}
  hint=TextView(this).apply{text="مرحله ۱: کل بدن را داخل کادر بگذار و ثابت بایست.";textSize=15f;setTextColor(Color.WHITE);setPadding(0,12,0,12)}
  action=Button(this).apply{text="📸 ثبت اسکن بدن";setOnClickListener{capture()}}
  panel.addView(title);panel.addView(hint);panel.addView(action)
  root.addView(panel,FrameLayout.LayoutParams(-1,-2).apply{gravity=android.view.Gravity.TOP;leftMargin=12;rightMargin=12;topMargin=12})
  setContentView(root)
 }
 private fun startCamera(){
  val future=ProcessCameraProvider.getInstance(this)
  future.addListener({
   val provider=future.get()
   val p=Preview.Builder().setTargetRotation(preview.display.rotation).build();p.setSurfaceProvider(preview.surfaceProvider)
   val a=ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setTargetResolution(android.util.Size(960,540)).build()
   a.setAnalyzer(executor){proxy->analyze(proxy)}
   provider.unbindAll();provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,p,a)
  },ContextCompat.getMainExecutor(this))
 }
 private fun analyze(proxy:ImageProxy){
  try{
   val bmp=toBitmap(proxy);frameW=bmp.width.toFloat();frameH=bmp.height.toFloat()
   val image=BitmapImageBuilder(bmp).build();val ts=System.currentTimeMillis()
   poseLandmarker?.detectAsync(image,ts);detector?.detectAsync(image,ts)
  }catch(_:Exception){}finally{proxy.close()}
 }
 private fun capture(){
  if(step==0){
   if(pose.size<33){hint.text="بدن کامل دیده نشد؛ کمی عقب‌تر برو و دوباره امتحان کن.";return}
   getSharedPreferences("hoopai_calibration",0).edit().putBoolean("body",true).putFloat("bodyHeight",bodyHeight()).apply()
   step=1;title.text="🧠 کالیبراسیون HoopAI • ۲/۲";hint.text="توپ را روی زمین بگذار، کامل داخل کادر باشد، سپس ثبت کن.";action.text="📸 ثبت اسکن توپ"
  }else{
   if(ball==null){hint.text="توپ هنوز مطمئن تشخیص داده نشده؛ توپ را واضح‌تر داخل کادر بگذار.";return}
   getSharedPreferences("hoopai_calibration",0).edit().putBoolean("ball",true).putFloat("ballSize",ball!!.width()).apply()
   getSharedPreferences("hoopai_calibration",0).edit().putBoolean("ready",true).apply()
   startActivity(Intent(this,AiWorkoutActivity::class.java).apply{putExtra("category",category);putExtra("drill",drill)})
   finish()
  }
 }
 private fun bodyHeight():Float{
  if(pose.size<33)return 0f
  return abs(pose[0].y()-maxOf(pose[27].y(),pose[28].y()))
 }
 private fun onPose(r:PoseLandmarkerResult){pose=r.landmarks().firstOrNull()?:emptyList();emit()}
 private fun onObjects(r:ObjectDetectorResult){
  var best:RectF?=null
  for(d in r.detections()){val c=d.categories().maxByOrNull{it.score()}?:continue;val n=(c.categoryName()?:"").lowercase();if(n.contains("ball")&&c.score()>.3f)best=RectF(d.boundingBox().left/frameW,d.boundingBox().top/frameH,d.boundingBox().right/frameW,d.boundingBox().bottom/frameH)}
  ball=best;emit()
 }
 private fun emit(){runOnUiThread{overlay.setState(emptyList(),ball,pose,null,null)}}
 private fun toBitmap(proxy:ImageProxy):Bitmap{
  val image=proxy.image?:throw IllegalStateException()
  val y=image.planes[0].buffer;val u=image.planes[1].buffer;val v=image.planes[2].buffer
  val ys=y.remaining();val us=u.remaining();val vs=v.remaining();val nv=ByteArray(ys+us+vs)
  y.get(nv,0,ys);v.get(nv,ys,vs);u.get(nv,ys+vs,us)
  val yuv=android.graphics.YuvImage(nv,android.graphics.ImageFormat.NV21,image.width,image.height,null)
  val out=ByteArrayOutputStream();yuv.compressToJpeg(Rect(0,0,image.width,image.height),82,out)
  var b=BitmapFactory.decodeByteArray(out.toByteArray(),0,out.size())
  val m=Matrix();m.postRotate(proxy.imageInfo.rotationDegrees.toFloat())
  return Bitmap.createBitmap(b,0,0,b.width,b.height,m,true)
 }
 override fun onDestroy(){poseLandmarker?.close();detector?.close();executor.shutdown();super.onDestroy()}
}