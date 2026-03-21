package com.sunmi.scanner;

import android.view.KeyEvent;

interface IScanInterface {
    void sendKeyEvent(in KeyEvent key);
    void scan();
    void stop();
    int getScannerModel();
}
