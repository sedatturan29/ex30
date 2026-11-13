package com.ex30.batarya;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;

/** Android Auto giriş noktası. Kişisel kullanım için tüm Android Auto sürümlerine izin verir. */
public class EX30CarAppService extends CarAppService {

    @NonNull
    @Override
    public HostValidator createHostValidator() {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
    }

    @NonNull
    @Override
    public Session onCreateSession() {
        return new Session() {
            {
                getLifecycle().addObserver((androidx.lifecycle.LifecycleEventObserver) (src, ev) -> {
                    if (ev == androidx.lifecycle.Lifecycle.Event.ON_CREATE) WebHost.onCarStart(getCarContext());
                    else if (ev == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) WebHost.onCarStop();
                });
            }

            @NonNull
            @Override
            public Screen onCreateScreen(@NonNull Intent intent) {
                return new BatteryScreen(getCarContext());
            }
        };
    }
}
