package android.myapplication;

/**
 * Created by Excalibur on 2017/5/30.
 */

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public class ChatService {
    private static final String NAME="MainActivity";

    // UUID-->通用唯一识别码，能唯一地辨识咨询
    private static final UUID MY_UUID=UUID.fromString(
            "00001101-0000-1000-8000-00805F9B34FB");//串口
           // "fa87c0d0-afac-11de-8a39-0800200c9a66");

    private final BluetoothAdapter mAdapter;
    private final Handler mHandler ;
    private AcceptThread mAcceptThread;
    private ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;
    private int mState;

    public static final int STATE_NONE = 0;
    public static final int STATE_LISTEN = 1;
    public static final int STATE_CONNECTING = 2;
    public static final int STATE_CONNECTED = 3;

    public ChatService(Context context, Handler handler){
        mAdapter=BluetoothAdapter.getDefaultAdapter();
        mState = STATE_NONE;
        mHandler = handler;
    }

    private synchronized void setState(int state){

        mState = state;
        mHandler.obtainMessage(MainActivity.MESSAGE_STATE_CHANGE,state,-1)
                .sendToTarget();
    }

    public synchronized int getState(){
        return mState;
    }

    public synchronized void start(){
        if(mConnectThread !=null){
            mConnectThread.cancel();
            mConnectThread=null;
        }

        if(mConnectedThread !=null){
            mConnectedThread.cancel();
            mConnectedThread=null;
        }

        if (mAcceptThread==null){
            mAcceptThread=new AcceptThread();
            mAcceptThread.start();
        }
        setState(STATE_LISTEN);
    }

    // 取消 Connecting Connected状态下的相关线程，然后运行新的mConnectThread线程
    public synchronized void connect(BluetoothDevice device){
        if(mState == STATE_CONNECTED){
            if(mConnectThread !=null){
                mConnectThread.cancel();
                mConnectThread=null;
            }
        }

        if(mConnectedThread !=null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        if(mAcceptThread != null){
            mAcceptThread.cancel();
            mAcceptThread =null;
        }

        mConnectThread = new ConnectThread(device);
        mConnectThread.start();
        setState(STATE_CONNECTING);
    }

    // 开启一个ConnectThread来管理对应的当前连接。之前取消任意现存的mConnectThread
    // mConnectThread，mAcceptThread线程，然后开启新的mConnectThread，传入当前
    // 刚刚接受的socket连接，最后通过Handler来通知UI连接
    public synchronized void connected(BluetoothSocket socket,
                                       BluetoothDevice device){
        if(mConnectThread !=null){
            mConnectThread.cancel();
            mConnectThread=null;
        }

        if(mConnectedThread !=null){
            mConnectedThread.cancel();
            mConnectedThread=null;
        }
        if(mAcceptThread !=null){
            mAcceptThread.cancel();
            mAcceptThread=null;
        }

        mConnectedThread=new ConnectedThread(socket);
        mConnectedThread.start();

        Message msg = mHandler.obtainMessage(MainActivity.MESSAGE_DEVICE_NAME);
        Bundle bundle = new Bundle();
        bundle.putString(MainActivity.DEVICE_NAME,device.getName());
        msg.setData(bundle);
        mHandler.sendMessage(msg);
        setState(STATE_CONNECTED);
    }

    // 停止所有相关线程，设当前状态为none
    public synchronized void stop(){
        if(mConnectThread !=null){
            mConnectThread.cancel();
            mConnectThread=null;
        }

        if(mConnectedThread !=null){
            mConnectedThread.cancel();
            mConnectedThread=null;
        }
        if(mAcceptThread !=null){
            mAcceptThread.cancel();
            mAcceptThread=null;
        }
        setState(STATE_NONE);
    }

    // 在STATE_CONNECTED状态下，调用mConnectedThread里的write方法，写入byte
    public void write(byte[]out){
        ConnectedThread r;
        synchronized (this){
            if(mState != STATE_CONNECTED)
                return;
            r = mConnectedThread;
        }
        r.write(out);
    }

    // 连接失败的时候处理，通知UI，并设为STATE_LISTEN状态
    private void connectionFailed(){
        setState(STATE_LISTEN);

        Message msg = mHandler.obtainMessage(MainActivity.MESSAGE_TOAST);
        Bundle bundle=new Bundle();
        bundle.putString(MainActivity.TOAST,"链接不到设备");
        msg.setData(bundle);
        mHandler.sendMessage(msg);

        ChatService.this.start();
    }

    // 当连接失去的时候，设为STATE_LISTEN
    private void connectionLost(){
        setState(STATE_LISTEN);

        Message msg = mHandler.obtainMessage(MainActivity.MESSAGE_TOAST);
        Bundle bundle=new Bundle();
        bundle.putString(MainActivity.TOAST,"设备链接中断");
        msg.setData(bundle);
        mHandler.sendMessage(msg);

        ChatService.this.start();
    }

    // 创建监听线程，准备接受新连接。使用阻塞方式，调用BluetoothServerSocket.accept()
    private class AcceptThread extends Thread{
        private final BluetoothServerSocket mmServerSocket;

        public AcceptThread(){
            BluetoothServerSocket tmp = null;
            try{
                tmp = mAdapter.listenUsingRfcommWithServiceRecord(NAME,MY_UUID);
            }catch (IOException e){}
            mmServerSocket = tmp;
        }


        public void run(){
        BluetoothSocket socket= null;

        while(mState != STATE_CONNECTED){
            try{
                socket = mmServerSocket.accept();
            }catch (IOException e) {
                break;
            }
            if(socket != null){
                connected(socket,socket.getRemoteDevice());
                try{
                    mmServerSocket.close();
                }catch (IOException e){}
            }
        }
    }

        public void cancel(){
            try{
                mmServerSocket.close();
            }catch (IOException e){}
        }
}

    // 连接线程，专门用来对外发出连接对方蓝牙的请求并进行处理
    // 构造函数里通过BluetoothDevice.createRfcommSocketToServiceRecord(),
    // 从待连接的device产生BluetoothSocket，然后在run方法中connect
    // 成功后调用 BluetoothChatService的connnected（）方法，定义cancel（）在关闭线程时能关闭socket
    private class ConnectThread extends Thread{
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        public ConnectThread(BluetoothDevice device){
            // Use a temporary object that is later assigned to mmSocket,
            // because mmSocket is final
            mmDevice=device;
            BluetoothSocket tmp = null;
            // Get a BluetoothSocket to connect with the given BluetoothDevice
            try{
                // MY_UUID is the app's UUID string, also used by the server code
                tmp = device.createRfcommSocketToServiceRecord(MY_UUID);
            }catch (IOException e){}
            mmSocket = tmp;
        }

        public void run(){
            // Cancel discovery because it will slow down the connection
            mAdapter.cancelDiscovery();
            try{
                // Connect the device through the socket. This will block
                // until it succeeds or throws an exception
                mmSocket.connect();
            }catch (IOException e){
                connectionFailed();
                // Unable to connect; close the socket and get out
                try{
                    mmSocket.close();
                }catch (IOException e2){}

                //ChatService.this.start();
               return;
           }
            synchronized(ChatService.this){
                mConnectedThread = null;
           }
            connected(mmSocket,mmDevice);
        }

        public void cancel(){
           /* try{
                mmSocket.close();
            }catch (IOException e){}*/
        }
    }

    // 双方蓝牙连接后一直运行的线程。构造函数中设置输入输出流。
    // Run方法中使用阻塞模式的InputStream.read()循环读取输入流
    // 然后psot到UI线程中更新聊天信息。也提供了write()将聊天消息写入输出流传输至对方，
    // 传输成功后回写入UI线程。最后cancel()关闭连接的socket

    private class ConnectedThread extends Thread{
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        public ConnectedThread(BluetoothSocket socket){
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut=null;
            // Get the input and output streams, using temp objects because
            // member streams are final
            try{
                tmpIn=mmSocket.getInputStream();
                tmpOut=mmSocket.getOutputStream();
            }catch (IOException e){}

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run(){
            byte[]buffer=new byte[1024];
            int bytes;
            while (true){
                try{
                    bytes = mmInStream.read(buffer);
                    mHandler.obtainMessage(MainActivity.MESSAGE_READ,bytes,-1,buffer).sendToTarget();
                }catch (IOException e){
                    connectionLost();
                    break;
                }
            }
        }

        public void write(byte[]buffer){
            try{
                mmOutStream.write(buffer);
            }catch (IOException e){
                Log.d("MainActivity","Send Fail");
            }
            mHandler.obtainMessage(MainActivity.MESSAGE_WRITE,buffer).sendToTarget();
        }

        public void cancel(){
            try{
                mmSocket.close();
            }catch (IOException e){}
        }
    }

}
