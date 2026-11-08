package com.um.clearflow;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.List;

public class MapViewModel extends ViewModel {

    private final CampusRepository repository = CampusRepository.getInstance();
    private final MutableLiveData<String> selectedFilter = new MutableLiveData<>("All");
    private final MutableLiveData<Boolean> routeEnabled = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> sheltersVisible = new MutableLiveData<>(true);
    private final MutableLiveData<Integer> originIndex = new MutableLiveData<>(0);
    private final MutableLiveData<Integer> destinationIndex = new MutableLiveData<>(0);

    public LiveData<List<HazardReport>> getReports() {
        return repository.getReportsLiveData();
    }

    public LiveData<String> getBroadcastBanner() {
        return repository.getBroadcastBannerLiveData();
    }

    public LiveData<String> getSelectedFilter() {
        return selectedFilter;
    }

    public void setFilter(String filter) {
        selectedFilter.setValue(filter);
    }

    public LiveData<Boolean> getRouteEnabled() {
        return routeEnabled;
    }

    public void setRouteEnabled(boolean enabled) {
        routeEnabled.setValue(enabled);
    }

    public LiveData<Boolean> getSheltersVisible() {
        return sheltersVisible;
    }

    public void setSheltersVisible(boolean visible) {
        sheltersVisible.setValue(visible);
    }

    public LiveData<Integer> getOriginIndex() {
        return originIndex;
    }

    public void setOriginIndex(int idx) {
        originIndex.setValue(idx);
    }

    public LiveData<Integer> getDestinationIndex() {
        return destinationIndex;
    }

    public void setDestinationIndex(int idx) {
        destinationIndex.setValue(idx);
    }
}