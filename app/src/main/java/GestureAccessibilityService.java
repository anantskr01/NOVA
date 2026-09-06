package com.aircontrol;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Point;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public class GestureAccessibilityService extends AccessibilityService {
    private static final String TAG="NovaAccessibility";
    private static GestureAccessibilityService instance;
    private static final long SWIPE_DURATION=45;
    private static final float HORIZONTAL_DISTANCE=0.16f, VERTICAL_DISTANCE=0.16f;
    private final Handler mainHandler=new Handler(Looper.getMainLooper());
    private boolean fingerGestureRunning=false;
    private float pendingFingerX=0f,pendingFingerY=0f;
    private static final float MAX_PENDING_X=0.055f,MAX_PENDING_Y=0.055f;

    @Override protected void onServiceConnected(){super.onServiceConnected();instance=this;Log.d(TAG,"NOVA ACCESSIBILITY SERVICE CONNECTED");}
    @Override public void onAccessibilityEvent(AccessibilityEvent event){NovaFoodAgent agent=NovaFoodAgent.getActive();if(agent!=null)agent.onAccessibilityEvent();}
    @Override public void onInterrupt(){Log.d(TAG,"NOVA ACCESSIBILITY INTERRUPTED");}
    public static GestureAccessibilityService getInstance(){return instance;}

    public String getVisibleTextSummary(){AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null)return"I cannot read the current screen. Accessibility window content may be unavailable.";StringBuilder out=new StringBuilder();collectText(root,out,0);if(out.length()==0)return"I cannot find readable text on the current screen.";return out.length()>1800?out.substring(0,1800):out.toString();}
    public String getUiSnapshot(){AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null)return"No accessibility window is available.";StringBuilder out=new StringBuilder();collectUiSnapshot(root,out,0);if(out.length()==0)return"No readable UI elements found.";return out.length()>10000?out.substring(0,10000):out.toString();}
    private void collectUiSnapshot(AccessibilityNodeInfo node,StringBuilder out,int depth){if(node==null||depth>22||out.length()>10000)return;CharSequence text=node.getText(),desc=node.getContentDescription();String label=text!=null&&text.length()>0?text.toString().trim():(desc!=null?desc.toString().trim():"");if(!label.isEmpty()||node.isClickable()||node.isEditable()){android.graphics.Rect b=new android.graphics.Rect();node.getBoundsInScreen(b);out.append("• ").append(label.isEmpty()?"[unlabeled]":label).append(" | class=").append(node.getClassName()).append(" | clickable=").append(node.isClickable()).append(" | enabled=").append(node.isEnabled()).append(" | editable=").append(node.isEditable()).append(" | bounds=").append(b.left).append(',').append(b.top).append('-').append(b.right).append(',').append(b.bottom).append('\n');}for(int i=0;i<node.getChildCount();i++)collectUiSnapshot(node.getChild(i),out,depth+1);}
    private void collectText(AccessibilityNodeInfo node,StringBuilder out,int depth){if(node==null||depth>25||out.length()>1800)return;CharSequence t=node.getText(),d=node.getContentDescription();if(t!=null&&t.length()>0){String v=t.toString().trim();if(!v.isEmpty())out.append(v).append('\n');}else if(d!=null&&d.length()>0){String v=d.toString().trim();if(!v.isEmpty())out.append(v).append('\n');}for(int i=0;i<node.getChildCount();i++)collectText(node.getChild(i),out,depth+1);}

    public boolean setTextOnAnyEditable(String text){if(text==null)return false;AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null)return false;AccessibilityNodeInfo node=findEditable(root,0);return node!=null&&setText(node,text);}
    public boolean setTextOnBestEditable(String label,String text){AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null)return false;AccessibilityNodeInfo target=findBestEditable(root,label==null?"":label.toLowerCase(),0);return target!=null&&setText(target,text);}
    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n,int depth){if(n==null||depth>22)return null;if(n.isVisibleToUser()&&n.isEnabled()&&n.isEditable())return n;for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo r=findEditable(n.getChild(i),depth+1);if(r!=null)return r;}return null;}
    private AccessibilityNodeInfo findBestEditable(AccessibilityNodeInfo n,String label,int depth){if(n==null||depth>22)return null;AccessibilityNodeInfo best=null;int bestScore=-1;for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo r=findBestEditable(n.getChild(i),label,depth+1);int s=editableScore(r,label);if(s>bestScore){bestScore=s;best=r;}}int self=editableScore(n,label);if(self>bestScore)return n;return best;}
    private int editableScore(AccessibilityNodeInfo n,String label){if(n==null||!n.isVisibleToUser()||!n.isEnabled()||!n.isEditable())return-1;String h="";if(Build.VERSION.SDK_INT>=26&&n.getHintText()!=null)h=n.getHintText().toString().toLowerCase();String t=n.getText()==null?"":n.getText().toString().toLowerCase();String d=n.getContentDescription()==null?"":n.getContentDescription().toString().toLowerCase();int s=10;if(!label.isEmpty()&&(h.contains(label)||t.contains(label)||d.contains(label)))s+=100;return s;}
    private boolean setText(AccessibilityNodeInfo node,String text){try{node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);Bundle b=new Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b);}catch(Exception e){Log.e(TAG,"SET TEXT ERROR",e);return false;}}

    public boolean clickVisibleIndex(int requestedIndex){if(requestedIndex<1)return false;AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null)return false;java.util.ArrayList<AccessibilityNodeInfo> nodes=new java.util.ArrayList<>();collectClickableNodes(root,nodes,0);if(requestedIndex>nodes.size())return false;return clickNodeOrParent(nodes.get(requestedIndex-1));}
    private void collectClickableNodes(AccessibilityNodeInfo n,java.util.ArrayList<AccessibilityNodeInfo> out,int depth){if(n==null||depth>18||out.size()>=50)return;CharSequence t=n.getText(),d=n.getContentDescription();if(n.isClickable()&&n.isEnabled()&&((t!=null&&t.length()>0)||(d!=null&&d.length()>0)))out.add(n);for(int i=0;i<n.getChildCount();i++)collectClickableNodes(n.getChild(i),out,depth+1);}
    public boolean clickText(String requested){if(requested==null||requested.trim().isEmpty())return false;AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null)return false;AccessibilityNodeInfo target=findBestTextNode(root,requested.trim().toLowerCase(),0);return target!=null&&clickNodeOrParent(target);}
    private AccessibilityNodeInfo findBestTextNode(AccessibilityNodeInfo node,String requested,int depth){if(node==null||depth>18)return null;AccessibilityNodeInfo best=null;int bestScore=-1;for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo c=findBestTextNode(node.getChild(i),requested,depth+1);int s=scoreNode(c,requested);if(s>bestScore){bestScore=s;best=c;}}int self=scoreNode(node,requested);if(self>bestScore)return node;return best;}
    private int scoreNode(AccessibilityNodeInfo node,String requested){if(node==null||!node.isVisibleToUser())return-1;String v=node.getText()==null?"":node.getText().toString().trim().toLowerCase();String d=node.getContentDescription()==null?"":node.getContentDescription().toString().trim().toLowerCase();if(v.isEmpty()&&d.isEmpty())return-1;int s=0;if(v.equals(requested))s+=100;else if(v.contains(requested))s+=70;if(d.equals(requested))s+=95;else if(d.contains(requested))s+=65;if(node.isClickable())s+=35;if(node.isEnabled())s+=10;if(node.isFocusable())s+=5;return s;}
    private boolean clickNodeOrParent(AccessibilityNodeInfo target){if(target==null||!target.isVisibleToUser())return false;try{if(target.isEnabled()&&target.isClickable()&&target.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;AccessibilityNodeInfo p=target.getParent();int depth=0;while(p!=null&&depth++<6){if(p.isEnabled()&&p.isClickable()&&p.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;p=p.getParent();}}catch(Exception e){Log.e(TAG,"CLICK NODE ERROR",e);}return false;}
    public boolean performGlobalActionPublic(int action){try{return performGlobalAction(action);}catch(Exception e){Log.e(TAG,"GLOBAL ACTION ERROR",e);return false;}}
    private Point getScreenSize(){Point s=new Point();try{s.x=getResources().getDisplayMetrics().widthPixels;s.y=getResources().getDisplayMetrics().heightPixels;}catch(Exception e){Log.e(TAG,"SCREEN SIZE ERROR",e);}if(s.x<=0)s.x=1080;if(s.y<=0)s.y=1920;return s;}
    private boolean performSwipe(float sx,float sy,float ex,float ey,GestureResultCallback cb){Path p=new Path();p.moveTo(sx,sy);p.lineTo(ex,ey);GestureDescription.StrokeDescription stroke=new GestureDescription.StrokeDescription(p,0,SWIPE_DURATION);boolean accepted=dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(),cb,null);Log.d(TAG,"dispatchGesture accepted = "+accepted);return accepted;}
    private void performSwipe(float sx,float sy,float ex,float ey){performSwipe(sx,sy,ex,ey,new GestureResultCallback(){@Override public void onCompleted(GestureDescription g){Log.d(TAG,"GESTURE COMPLETED");}@Override public void onCancelled(GestureDescription g){Log.d(TAG,"GESTURE CANCELLED");}});}
    public void swipeRight(){Point s=getScreenSize();float y=s.y*.50f,c=s.x*.50f,d=s.x*HORIZONTAL_DISTANCE;performSwipe(c-d,y,c,y);}
    public void swipeLeft(){Point s=getScreenSize();float y=s.y*.50f,c=s.x*.50f,d=s.x*HORIZONTAL_DISTANCE;performSwipe(c+d,y,c,y);}
    public void swipeUp(){Point s=getScreenSize();float x=s.x*.50f,c=s.y*.50f,d=s.y*VERTICAL_DISTANCE;performSwipe(x,c+d,x,c);}
    public void swipeDown(){Point s=getScreenSize();float x=s.x*.50f,c=s.y*.50f,d=s.y*VERTICAL_DISTANCE;performSwipe(x,c-d,x,c);}
    public void moveFinger(float dx,float dy){if(Math.abs(dx)<.0005f&&Math.abs(dy)<.0005f)return;mainHandler.post(()->{pendingFingerX=clamp(pendingFingerX+dx,-MAX_PENDING_X,MAX_PENDING_X);pendingFingerY=clamp(pendingFingerY+dy,-MAX_PENDING_Y,MAX_PENDING_Y);dispatchPendingFingerMovement();});}
    private void dispatchPendingFingerMovement(){if(fingerGestureRunning)return;if(Math.abs(pendingFingerX)<.002f&&Math.abs(pendingFingerY)<.002f)return;float dx=pendingFingerX,dy=pendingFingerY;pendingFingerX=0;pendingFingerY=0;Point s=getScreenSize();float cx=s.x*.5f,cy=s.y*.5f,sdx=clamp(dx*s.x*1.8f,-s.x*.06f,s.x*.06f),sdy=clamp(dy*s.y*1.8f,-s.y*.06f,s.y*.06f);fingerGestureRunning=true;boolean accepted=performSwipe(cx,cy,clamp(cx+sdx,5,s.x-5),clamp(cy+sdy,5,s.y-5),new GestureResultCallback(){@Override public void onCompleted(GestureDescription g){fingerGestureRunning=false;dispatchPendingFingerMovement();}@Override public void onCancelled(GestureDescription g){fingerGestureRunning=false;dispatchPendingFingerMovement();}});if(!accepted){fingerGestureRunning=false;pendingFingerX=clamp(pendingFingerX+dx,-MAX_PENDING_X,MAX_PENDING_X);pendingFingerY=clamp(pendingFingerY+dy,-MAX_PENDING_Y,MAX_PENDING_Y);mainHandler.postDelayed(this::dispatchPendingFingerMovement,40);}}
    private float clamp(float v,float min,float max){return Math.max(min,Math.min(max,v));}
    @Override public void onDestroy(){mainHandler.removeCallbacksAndMessages(null);pendingFingerX=0;pendingFingerY=0;fingerGestureRunning=false;if(instance==this)instance=null;NovaFoodAgent a=NovaFoodAgent.getActive();if(a!=null)a.cancel();Log.d(TAG,"NOVA ACCESSIBILITY SERVICE DESTROYED");super.onDestroy();}
}
