// Generated by view binder compiler. Do not edit!
package com.example.aifloatingball.databinding;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;
import androidx.viewbinding.ViewBindings;
import com.example.aifloatingball.R;
import java.lang.NullPointerException;
import java.lang.Override;
import java.lang.String;

public final class CardLayoutThemeBinding implements ViewBinding {
  @NonNull
  private final CoordinatorLayout rootView;

  @NonNull
  public final RecyclerView cardRecyclerView;

  private CardLayoutThemeBinding(@NonNull CoordinatorLayout rootView,
      @NonNull RecyclerView cardRecyclerView) {
    this.rootView = rootView;
    this.cardRecyclerView = cardRecyclerView;
  }

  @Override
  @NonNull
  public CoordinatorLayout getRoot() {
    return rootView;
  }

  @NonNull
  public static CardLayoutThemeBinding inflate(@NonNull LayoutInflater inflater) {
    return inflate(inflater, null, false);
  }

  @NonNull
  public static CardLayoutThemeBinding inflate(@NonNull LayoutInflater inflater,
      @Nullable ViewGroup parent, boolean attachToParent) {
    View root = inflater.inflate(R.layout.card_layout_theme, parent, false);
    if (attachToParent) {
      parent.addView(root);
    }
    return bind(root);
  }

  @NonNull
  public static CardLayoutThemeBinding bind(@NonNull View rootView) {
    // The body of this method is generated in a way you would not otherwise write.
    // This is done to optimize the compiled bytecode for size and performance.
    int id;
    missingId: {
      id = R.id.card_recycler_view;
      RecyclerView cardRecyclerView = ViewBindings.findChildViewById(rootView, id);
      if (cardRecyclerView == null) {
        break missingId;
      }

      return new CardLayoutThemeBinding((CoordinatorLayout) rootView, cardRecyclerView);
    }
    String missingId = rootView.getResources().getResourceName(id);
    throw new NullPointerException("Missing required view with ID: ".concat(missingId));
  }
}
