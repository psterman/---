// Generated by view binder compiler. Do not edit!
package com.example.aifloatingball.databinding;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.viewbinding.ViewBinding;
import androidx.viewbinding.ViewBindings;
import com.example.aifloatingball.R;
import java.lang.NullPointerException;
import java.lang.Override;
import java.lang.String;

public final class SearchEngineCardBinding implements ViewBinding {
  @NonNull
  private final CardView rootView;

  @NonNull
  public final ImageButton btnBack;

  @NonNull
  public final ImageButton btnForward;

  @NonNull
  public final ImageButton btnMinimize;

  @NonNull
  public final ImageButton btnRefresh;

  @NonNull
  public final FrameLayout contentContainer;

  @NonNull
  public final LinearLayout controlBar;

  @NonNull
  public final TextView engineTitle;

  @NonNull
  public final ImageButton optionsButton;

  @NonNull
  public final RelativeLayout titleBar;

  @NonNull
  public final WebView webView;

  private SearchEngineCardBinding(@NonNull CardView rootView, @NonNull ImageButton btnBack,
      @NonNull ImageButton btnForward, @NonNull ImageButton btnMinimize,
      @NonNull ImageButton btnRefresh, @NonNull FrameLayout contentContainer,
      @NonNull LinearLayout controlBar, @NonNull TextView engineTitle,
      @NonNull ImageButton optionsButton, @NonNull RelativeLayout titleBar,
      @NonNull WebView webView) {
    this.rootView = rootView;
    this.btnBack = btnBack;
    this.btnForward = btnForward;
    this.btnMinimize = btnMinimize;
    this.btnRefresh = btnRefresh;
    this.contentContainer = contentContainer;
    this.controlBar = controlBar;
    this.engineTitle = engineTitle;
    this.optionsButton = optionsButton;
    this.titleBar = titleBar;
    this.webView = webView;
  }

  @Override
  @NonNull
  public CardView getRoot() {
    return rootView;
  }

  @NonNull
  public static SearchEngineCardBinding inflate(@NonNull LayoutInflater inflater) {
    return inflate(inflater, null, false);
  }

  @NonNull
  public static SearchEngineCardBinding inflate(@NonNull LayoutInflater inflater,
      @Nullable ViewGroup parent, boolean attachToParent) {
    View root = inflater.inflate(R.layout.search_engine_card, parent, false);
    if (attachToParent) {
      parent.addView(root);
    }
    return bind(root);
  }

  @NonNull
  public static SearchEngineCardBinding bind(@NonNull View rootView) {
    // The body of this method is generated in a way you would not otherwise write.
    // This is done to optimize the compiled bytecode for size and performance.
    int id;
    missingId: {
      id = R.id.btn_back;
      ImageButton btnBack = ViewBindings.findChildViewById(rootView, id);
      if (btnBack == null) {
        break missingId;
      }

      id = R.id.btn_forward;
      ImageButton btnForward = ViewBindings.findChildViewById(rootView, id);
      if (btnForward == null) {
        break missingId;
      }

      id = R.id.btn_minimize;
      ImageButton btnMinimize = ViewBindings.findChildViewById(rootView, id);
      if (btnMinimize == null) {
        break missingId;
      }

      id = R.id.btn_refresh;
      ImageButton btnRefresh = ViewBindings.findChildViewById(rootView, id);
      if (btnRefresh == null) {
        break missingId;
      }

      id = R.id.content_container;
      FrameLayout contentContainer = ViewBindings.findChildViewById(rootView, id);
      if (contentContainer == null) {
        break missingId;
      }

      id = R.id.control_bar;
      LinearLayout controlBar = ViewBindings.findChildViewById(rootView, id);
      if (controlBar == null) {
        break missingId;
      }

      id = R.id.engine_title;
      TextView engineTitle = ViewBindings.findChildViewById(rootView, id);
      if (engineTitle == null) {
        break missingId;
      }

      id = R.id.options_button;
      ImageButton optionsButton = ViewBindings.findChildViewById(rootView, id);
      if (optionsButton == null) {
        break missingId;
      }

      id = R.id.title_bar;
      RelativeLayout titleBar = ViewBindings.findChildViewById(rootView, id);
      if (titleBar == null) {
        break missingId;
      }

      id = R.id.web_view;
      WebView webView = ViewBindings.findChildViewById(rootView, id);
      if (webView == null) {
        break missingId;
      }

      return new SearchEngineCardBinding((CardView) rootView, btnBack, btnForward, btnMinimize,
          btnRefresh, contentContainer, controlBar, engineTitle, optionsButton, titleBar, webView);
    }
    String missingId = rootView.getResources().getResourceName(id);
    throw new NullPointerException("Missing required view with ID: ".concat(missingId));
  }
}
