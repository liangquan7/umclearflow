package com.um.clearflow;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.lang.ref.WeakReference;

/**
 * Hazard report list adapter. DiffUtil only rebinds cards whose content actually changed,
 * instead of tearing down and re-inflating every card on each LiveData emission.
 */
public class ReportAdapter extends ListAdapter<HazardReport, ReportAdapter.CardHolder> {

    private static final ExecutorService PHOTO_EXECUTOR = Executors.newFixedThreadPool(2);
    private static final LruCache<String, Bitmap> PHOTO_CACHE = new LruCache<String, Bitmap>(
            (int) (Runtime.getRuntime().maxMemory() / 8L)) {
        @Override
        protected int sizeOf(@NonNull String key, @NonNull Bitmap value) {
            return Math.max(1, value.getByteCount());
        }
    };

    public interface Actions {
        void onUpvote(HazardReport r);
        void onVoteResolve(HazardReport r);
        void onAdminStatus(HazardReport r);
    }

    private final Actions actions;
    private boolean isAdmin;

    public ReportAdapter(Actions actions) {
        super(DIFF);
        this.actions = actions;
    }

    /** Role affects every card's admin button, so rebind all when it changes. */
    @SuppressWarnings("NotifyDataSetChanged")
    public void setAdmin(boolean admin) {
        if (admin != isAdmin) {
            isAdmin = admin;
            notifyDataSetChanged();
        }
    }

    private static final DiffUtil.ItemCallback<HazardReport> DIFF = new DiffUtil.ItemCallback<HazardReport>() {
        @Override
        public boolean areItemsTheSame(@NonNull HazardReport a, @NonNull HazardReport b) {
            return Objects.equals(a.id, b.id);
        }

        @Override
        public boolean areContentsTheSame(@NonNull HazardReport a, @NonNull HazardReport b) {
            return Objects.equals(a.title, b.title)
                    && Objects.equals(a.description, b.description)
                    && Objects.equals(a.locationName, b.locationName)
                    && Objects.equals(a.severity, b.severity)
                    && Objects.equals(a.status, b.status)
                    && Objects.equals(a.timestamp, b.timestamp)
                    && Objects.equals(a.reporterEmail, b.reporterEmail)
                    && Objects.equals(a.aiTriageNote, b.aiTriageNote)
                    && Objects.equals(a.timeline, b.timeline)
                    && Objects.equals(a.photoUri, b.photoUri)
                    && a.upvotes == b.upvotes
                    && a.resolveVotes == b.resolveVotes
                    && a.latitude == b.latitude
                    && a.longitude == b.longitude;
        }
    };

    @NonNull
    @Override
    public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new CardHolder(parent.getContext());
    }

    @Override
    public void onBindViewHolder(@NonNull CardHolder h, int position) {
        HazardReport r = getItem(position);
        CampusRepository repo = CampusRepository.getInstance();
        boolean resolved = "Resolved".equalsIgnoreCase(r.status);

        h.header.setText(String.format(Locale.US, "[%s] %s • %s", r.severity, r.title, r.status));
        h.header.setTextColor("Critical".equalsIgnoreCase(r.severity)
                ? Color.rgb(185, 28, 28) : Color.rgb(15, 23, 42));
        h.meta.setText(String.format(Locale.US, "📍 %s (%.4f, %.4f)\n🕒 %s | Reporter: %s",
                r.locationName, r.latitude, r.longitude, r.timestamp, r.reporterEmail));
        h.body.setText(r.description + "\nAI / Triage: " + r.aiTriageNote);

        bindPhoto(h.photo, r.photoUri);

        h.timeline.setText("📋 Timeline:\n" + r.timeline);

        boolean upvoted = repo.hasUpvoted(r.id);
        h.upvote.setText((upvoted ? "👍 Upvoted (" : "👍 Upvote (") + r.upvotes + ")");
        h.upvote.setEnabled(!upvoted);
        h.upvote.setOnClickListener(v -> actions.onUpvote(r));

        boolean votedResolve = repo.hasVotedResolve(r.id);
        if (resolved) {
            h.resolve.setText("✅ Resolved");
            h.resolve.setEnabled(false);
        } else if (votedResolve && BuildConfig.DEBUG) {
            h.resolve.setText("🌤️ +Demo Vote (" + r.resolveVotes + "/3)");
            h.resolve.setEnabled(true);
        } else {
            h.resolve.setText(votedResolve ? "🌤️ Voted (" + r.resolveVotes + "/3)" : "🌤️ Clear? (" + r.resolveVotes + "/3)");
            h.resolve.setEnabled(!votedResolve);
        }
        h.resolve.setOnClickListener(v -> actions.onVoteResolve(r));

        // Students never see the JPPHB status button
        h.admin.setVisibility(isAdmin ? View.VISIBLE : View.GONE);
        h.admin.setOnClickListener(v -> actions.onAdminStatus(r));
    }

    private static void bindPhoto(@NonNull ImageView imageView, String path) {
        if (path == null || path.isEmpty()) {
            imageView.setTag(null);
            imageView.setImageBitmap(null);
            imageView.setVisibility(View.GONE);
            return;
        }

        imageView.setTag(path);
        imageView.setVisibility(View.VISIBLE);
        Bitmap cached = PHOTO_CACHE.get(path);
        if (cached != null && !cached.isRecycled()) {
            imageView.setImageBitmap(cached);
            return;
        }

        imageView.setImageBitmap(null);
        int reqWidth = imageView.getResources().getDisplayMetrics().widthPixels;
        int reqHeight = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 140, imageView.getResources().getDisplayMetrics());
        WeakReference<ImageView> imageRef = new WeakReference<>(imageView);
        PHOTO_EXECUTOR.execute(() -> {
            Bitmap decoded = decodeSampledPhoto(path, reqWidth, reqHeight);
            if (decoded == null) return;
            PHOTO_CACHE.put(path, decoded);
            ImageView target = imageRef.get();
            if (target != null) {
                target.post(() -> {
                    if (Objects.equals(target.getTag(), path)) target.setImageBitmap(decoded);
                });
            }
        });
    }

    private static Bitmap decodeSampledPhoto(String path, int reqWidth, int reqHeight) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;

        int sampleSize = 1;
        while (options.outWidth / (sampleSize * 2) >= reqWidth
                || options.outHeight / (sampleSize * 2) >= reqHeight) {
            sampleSize *= 2;
        }
        options.inSampleSize = sampleSize;
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(path, options);
    }

    static class CardHolder extends RecyclerView.ViewHolder {
        final TextView header, meta, body, timeline;
        final ImageView photo;
        final Button upvote, resolve, admin;

        CardHolder(Context ctx) {
            super(new LinearLayout(ctx));
            LinearLayout card = (LinearLayout) itemView;
            int pad = dp(ctx, 12);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(Color.WHITE);
            card.setPadding(pad, pad, pad, pad);
            card.setElevation(dp(ctx, 2));
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, dp(ctx, 10));
            card.setLayoutParams(lp);

            header = new TextView(ctx);
            header.setTextSize(14f);
            header.setTypeface(null, Typeface.BOLD);
            card.addView(header);

            meta = new TextView(ctx);
            meta.setTextSize(11f);
            meta.setTextColor(Color.rgb(100, 116, 139));
            card.addView(meta);

            body = new TextView(ctx);
            body.setTextSize(12f);
            body.setTextColor(Color.rgb(51, 65, 85));
            body.setPadding(0, dp(ctx, 4), 0, dp(ctx, 4));
            card.addView(body);

            photo = new ImageView(ctx);
            LinearLayout.LayoutParams photoLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 140));
            photoLp.setMargins(0, dp(ctx, 4), 0, dp(ctx, 6));
            photo.setLayoutParams(photoLp);
            photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
            photo.setBackgroundColor(Color.rgb(226, 232, 240));
            photo.setVisibility(View.GONE);
            card.addView(photo);

            timeline = new TextView(ctx);
            timeline.setTextSize(11f);
            timeline.setTextColor(Color.rgb(71, 85, 105));
            timeline.setBackgroundColor(Color.rgb(241, 245, 249));
            timeline.setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6));
            card.addView(timeline);

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(ctx, 6), 0, 0);
            upvote = smallButton(ctx);
            resolve = smallButton(ctx);
            admin = smallButton(ctx);
            admin.setText("⚙️ Demo Status");
            row.addView(upvote);
            row.addView(resolve);
            row.addView(admin);
            card.addView(row);
        }

        private static Button smallButton(Context ctx) {
            Button b = new Button(ctx);
            b.setTextSize(11f);
            return b;
        }

        private static int dp(Context ctx, int dp) {
            return (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, dp, ctx.getResources().getDisplayMetrics());
        }
    }
}
