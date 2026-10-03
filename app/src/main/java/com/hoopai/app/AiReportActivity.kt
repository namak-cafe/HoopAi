package com.hoopai.app

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity

class AiReportActivity:ComponentActivity(){
 override fun onCreate(b:Bundle?){
  super.onCreate(b)
  val drill=intent.getStringExtra("drill")?:""
  val attempts=intent.getIntExtra("attempts",0)
  val makes=intent.getIntExtra("makes",0)
  val acc=intent.getIntExtra("accuracy",0)
  val form=intent.getIntExtra("form",0)
  val mins=intent.getIntExtra("minutes",1)
  val feedback=intent.getStringExtra("feedback")?:"تمرینت ثبت شد."
  val formText=if(form==0)"داده کافی نیست" else form.toString()+"/100"
  val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,35,24,35);gravity=Gravity.CENTER_HORIZONTAL;setBackgroundColor(Color.rgb(7,17,28))}
  fun tv(t:String,s:Float)=TextView(this).apply{text=t;textSize=s;setTextColor(Color.WHITE);setPadding(0,8,0,8)}
  box.addView(tv("🏀 HoopAI",28f));box.addView(tv("گزارش تحلیل هوش مصنوعی",23f));box.addView(tv(drill,18f))
  box.addView(tv("دقت: "+acc+"%   •   تلاش: "+attempts+"   •   موفق: "+makes,18f))
  box.addView(tv("فرم حرکتی: "+formText,18f))
  box.addView(tv("زمان تمرین: "+mins+" دقیقه",16f))
  box.addView(tv("🤖 "+feedback,16f))
  box.addView(Button(this).apply{text="بازگشت به HoopAI";setOnClickListener{finish()}})
  setContentView(box)
 }
}
