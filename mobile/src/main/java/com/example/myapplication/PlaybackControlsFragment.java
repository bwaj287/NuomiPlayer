package com.example.myapplication;

import android.os.Bundle;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textview.MaterialTextView;

public class PlaybackControlsFragment extends Fragment {

    private MaterialTextView titleTv;
    private MaterialTextView artistTv;
    private MaterialTextView songCurrentProgress;
    private MaterialTextView songTotalTime;
    private MaterialTextView songInfoTv;
    private MusicSlider progressSlider;
    private MaterialButton previousButton;
    private FloatingActionButton playPauseButton;
    private MaterialButton nextButton;
    private MaterialButton shuffleButton;
    private MaterialButton repeatButton;

    public PlaybackControlsFragment() {
        super(R.layout.fragment_m3_player_playback_controls);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        titleTv = view.findViewById(R.id.title);
        artistTv = view.findViewById(R.id.text);
        songCurrentProgress = view.findViewById(R.id.songCurrentProgress);
        songTotalTime = view.findViewById(R.id.songTotalTime);
        songInfoTv = view.findViewById(R.id.songInfo);

        progressSlider = view.findViewById(R.id.progressSlider);
        previousButton = view.findViewById(R.id.previousButton);
        playPauseButton = view.findViewById(R.id.playPauseButton);
        nextButton = view.findViewById(R.id.nextButton);
        shuffleButton = view.findViewById(R.id.shuffleButton);
        repeatButton = view.findViewById(R.id.repeatButton);

        titleTv.setText("请先打开酷狗音乐并播放任意歌曲");
        artistTv.setText("等待读取播放器元数据");
        songInfoTv.setText("等待捕获 MediaSession / 歌词");

        progressSlider.setValueFrom(0);
        progressSlider.setValueTo(1000);
        progressSlider.setValue(0);

        previousButton.setOnClickListener(v -> withController(new ControllerAction() {
            @Override
            public void run(MediaControllerCompat controller) {
                controller.getTransportControls().skipToPrevious();
            }
        }));

        playPauseButton.setOnClickListener(v -> withController(new ControllerAction() {
            @Override
            public void run(MediaControllerCompat controller) {
                PlaybackStateCompat state = controller.getPlaybackState();
                if (state == null || state.getState() != PlaybackStateCompat.STATE_PLAYING) {
                    controller.getTransportControls().play();
                } else {
                    controller.getTransportControls().pause();
                }
            }
        }));

        nextButton.setOnClickListener(v -> withController(new ControllerAction() {
            @Override
            public void run(MediaControllerCompat controller) {
                controller.getTransportControls().skipToNext();
            }
        }));

        progressSlider.setListener(new MusicSlider.Listener() {
            @Override
            public void onProgressChanged(MusicSlider slider, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(MusicSlider slider) {
            }

            @Override
            public void onStopTrackingTouch(MusicSlider slider) {
                withController(new ControllerAction() {
                    @Override
                    public void run(MediaControllerCompat controller) {
                        controller.getTransportControls().seekTo(slider.getValue());
                    }
                });
            }
        });

        shuffleButton.setEnabled(false);
        shuffleButton.setAlpha(0.45f);
        repeatButton.setEnabled(false);
        repeatButton.setAlpha(0.45f);
    }

    public void updateTitle(String title) {
        if (titleTv != null && title != null && !title.isEmpty()) {
            titleTv.setText(title);
        }
    }

    public void updateArtist(String artist) {
        if (artistTv != null && artist != null && !artist.isEmpty()) {
            artistTv.setText(artist);
        }
    }

    public void updateSongInfo(String info) {
        if (songInfoTv != null && info != null) {
            songInfoTv.setText(info);
        }
    }

    public void updateProgressTime(long milliseconds) {
        if (songCurrentProgress != null) {
            songCurrentProgress.setText(formatTime(milliseconds));
        }
        if (progressSlider != null && !progressSlider.isTrackingTouch()) {
            progressSlider.setValue((int) milliseconds);
        }
    }

    public void updateTotalTime(long milliseconds) {
        if (songTotalTime != null) {
            songTotalTime.setText(formatTime(milliseconds));
        }
        if (progressSlider != null) {
            progressSlider.setValueTo((int) Math.max(milliseconds, 1));
        }
    }

    public void updatePlayPauseButton(int playbackState) {
        if (playPauseButton == null) {
            return;
        }
        if (playbackState == PlaybackStateCompat.STATE_PLAYING) {
            playPauseButton.setImageResource(R.drawable.ic_pause_m3_24dp);
        } else {
            playPauseButton.setImageResource(R.drawable.ic_play_m3_24dp);
        }
    }

    private void withController(ControllerAction action) {
        MediaControllerCompat controller = MediaControllerCompat.getMediaController(requireActivity());
        if (controller != null) {
            action.run(controller);
        }
    }

    private String formatTime(long milliseconds) {
        long safeMs = Math.max(milliseconds, 0L);
        long totalSeconds = safeMs / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("%02d:%02d", minutes, seconds);
    }

    private interface ControllerAction {
        void run(MediaControllerCompat controller);
    }
}
