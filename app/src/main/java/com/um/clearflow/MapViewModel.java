package com.um.clearflow;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.List;

public class MapViewModel extends ViewModel {

    private final CampusRepository repository = CampusRepository.getInstance();
    private final MutableLiveData<String> filterLiveData = new MutableLiveData<>("ALL");
    private final MutableLiveData<Boolean> safeRouteActiveLiveData = new MutableLiveData<>(false);

    public LiveData<List<HazardReport>> getReports() {
        return repository.getReportsLiveData();
    }

    public LiveData<String> getFilter() {
        return filterLiveData;
    }

    public LiveData<Boolean> isSafeRouteActive() {
        return safeRouteActiveLiveData;
    }

    public void setFilter(String filter) {
        filterLiveData.setValue(filter);
    }

    public void toggleSafeRoute() {
        Boolean current = safeRouteActiveLiveData.getValue();
        safeRouteActiveLiveData.setValue(current == null || !current);
    }

    public void upvoteReport(String id) {
        repository.upvoteReport(id);
    }
}