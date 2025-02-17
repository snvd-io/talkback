/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.compositor;

import static android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED;
import static com.google.android.accessibility.utils.output.SpeechController.QUEUE_MODE_INTERRUPT;
import static com.google.android.accessibility.utils.output.SpeechController.QUEUE_MODE_UNINTERRUPTIBLE_BY_NEW_SPEECH;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo.RangeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.annotation.IntDef;
import androidx.core.view.accessibility.AccessibilityEventCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.view.accessibility.AccessibilityRecordCompat;
import androidx.core.view.accessibility.AccessibilityWindowInfoCompat;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.compositor.parsetree.ParseTree;
import com.google.android.accessibility.utils.AccessibilityEventUtils;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import com.google.android.accessibility.utils.ImageContents;
import com.google.android.accessibility.utils.JsonUtils;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.Role;
import com.google.android.accessibility.utils.input.TextEventInterpretation;
import com.google.android.accessibility.utils.output.FailoverTextToSpeech.SpeechParam;
import com.google.android.accessibility.utils.output.FeedbackItem;
import com.google.android.accessibility.utils.output.SpeechCleanupUtils;
import com.google.android.accessibility.utils.output.SpeechController;
import com.google.android.accessibility.utils.output.SpeechController.SpeakOptions;
import com.google.android.accessibility.utils.output.SpeechController.UtteranceCompleteRunnable;
import com.google.android.accessibility.utils.output.Utterance;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Translates events into user visible feedback. */
public class Compositor {

  /////////////////////////////////////////////////////////////////////////////////
  // Constants

  private static final String TAG = "Compositor";

  /** Flavors used to load different configurations for different device types and applications. */
  @IntDef({FLAVOR_NONE, FLAVOR_ARC, FLAVOR_TV, FLAVOR_JASPER})
  @Retention(RetentionPolicy.SOURCE)
  public @interface Flavor {}

  public static final int FLAVOR_NONE = 0;
  public static final int FLAVOR_ARC = 1;
  public static final int FLAVOR_TV = 2;
  public static final int FLAVOR_JASPER = 3;

  /** Identity numbers for incoming events, including AccessibilityEvents & interpreted events. */
  @IntDef({
    EVENT_UNKNOWN,
    EVENT_SPOKEN_FEEDBACK_ON,
    EVENT_SPOKEN_FEEDBACK_DISABLED,
    EVENT_CAPS_LOCK_ON,
    EVENT_CAPS_LOCK_OFF,
    EVENT_NUM_LOCK_ON,
    EVENT_NUM_LOCK_OFF,
    EVENT_SCROLL_LOCK_ON,
    EVENT_SCROLL_LOCK_OFF,
    EVENT_ORIENTATION_PORTRAIT,
    EVENT_ORIENTATION_LANDSCAPE,
    EVENT_TYPE_INPUT_TEXT_CLEAR,
    EVENT_TYPE_INPUT_TEXT_REMOVE,
    EVENT_TYPE_INPUT_TEXT_ADD,
    EVENT_TYPE_INPUT_TEXT_REPLACE,
    EVENT_TYPE_INPUT_TEXT_PASSWORD_ADD,
    EVENT_TYPE_INPUT_TEXT_PASSWORD_REMOVE,
    EVENT_TYPE_INPUT_TEXT_PASSWORD_REPLACE,
    EVENT_TYPE_INPUT_CHANGE_INVALID,
    EVENT_TYPE_INPUT_SELECTION_FOCUS_EDIT_TEXT,
    EVENT_TYPE_INPUT_SELECTION_MOVE_CURSOR_TO_BEGINNING,
    EVENT_TYPE_INPUT_SELECTION_MOVE_CURSOR_TO_END,
    EVENT_TYPE_INPUT_SELECTION_MOVE_CURSOR_NO_SELECTION,
    EVENT_TYPE_INPUT_SELECTION_MOVE_CURSOR_WITH_SELECTION,
    EVENT_TYPE_INPUT_SELECTION_MOVE_CURSOR_SELECTION_CLEARED,
    EVENT_TYPE_INPUT_SELECTION_CUT,
    EVENT_TYPE_INPUT_SELECTION_PASTE,
    EVENT_TYPE_INPUT_SELECTION_TEXT_TRAVERSAL,
    EVENT_TYPE_INPUT_SELECTION_SELECT_ALL,
    EVENT_TYPE_INPUT_SELECTION_SELECT_ALL_WITH_KEYBOARD,
    EVENT_TYPE_INPUT_SELECTION_RESET_SELECTION,
    EVENT_SPEAK_HINT,
    EVENT_MAGNIFICATION_CHANGED,
    EVENT_SCROLL_POSITION,
    EVENT_INPUT_DESCRIBE_NODE,
    EVENT_TYPE_VIEW_ACCESSIBILITY_FOCUSED,
    EVENT_TYPE_VIEW_FOCUSED,
    EVENT_TYPE_VIEW_HOVER_ENTER,
    EVENT_TYPE_VIEW_CLICKED,
    EVENT_TYPE_VIEW_LONG_CLICKED,
    EVENT_TYPE_NOTIFICATION_STATE_CHANGED,
    EVENT_TYPE_WINDOW_CONTENT_CHANGED,
    EVENT_TYPE_VIEW_SELECTED,
    EVENT_TYPE_VIEW_SCROLLED,
    EVENT_TYPE_ANNOUNCEMENT,
    EVENT_TYPE_WINDOW_STATE_CHANGED,
    EVENT_TYPE_TOUCH_INTERACTION_START,
    EVENT_TYPE_TOUCH_INTERACTION_END,
    EVENT_TYPE_VIEW_TEXT_CHANGED,
    EVENT_TYPE_VIEW_TEXT_SELECTION_CHANGED,
    EVENT_TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY,
  })
  @Retention(RetentionPolicy.SOURCE)
  public @interface Event {}

  // Events start from a large number to avoid conflict with AccessibilityEvent.getEventType()
  private static final int BASE_EVENT_ID = TextEventInterpretation.AFTER_TEXT_EVENTS;
  public static final int EVENT_UNKNOWN = BASE_EVENT_ID - 1;
  public static final int EVENT_SPOKEN_FEEDBACK_ON = BASE_EVENT_ID;
  public static final int EVENT_SPOKEN_FEEDBACK_DISABLED = BASE_EVENT_ID + 3;
  public static final int EVENT_CAPS_LOCK_ON = BASE_EVENT_ID + 4;
  public static final int EVENT_CAPS_LOCK_OFF = BASE_EVENT_ID + 5;
  public static final int EVENT_NUM_LOCK_ON = BASE_EVENT_ID + 6;
  public static final int EVENT_NUM_LOCK_OFF = BASE_EVENT_ID + 7;
  public static final int EVENT_SCROLL_LOCK_ON = BASE_EVENT_ID + 8;
  public static final int EVENT_SCROLL_LOCK_OFF = BASE_EVENT_ID + 9;
  public static final int EVENT_ORIENTATION_PORTRAIT = BASE_EVENT_ID + 10;
  public static final int EVENT_ORIENTATION_LANDSCAPE = BASE_EVENT_ID + 11;
  public static final int EVENT_SPEAK_HINT = BASE_EVENT_ID + 12;
  public static final int EVENT_SCROLL_POSITION = BASE_EVENT_ID + 13;
  public static final int EVENT_INPUT_DESCRIBE_NODE = BASE_EVENT_ID + 14;
  public static final int EVENT_MAGNIFICATION_CHANGED = BASE_EVENT_ID + 15;

  public static final int BASE_TEXT_EVENT_ID = BASE_EVENT_ID + 100;
  public static final int EVENT_TYPE_INPUT_TEXT_CLEAR = TextEventInterpretation.TEXT_CLEAR;
  public static final int EVENT_TYPE_INPUT_TEXT_REMOVE = TextEventInterpretation.TEXT_REMOVE;
  public static final int EVENT_TYPE_INPUT_TEXT_ADD = TextEventInterpretation.TEXT_ADD;
  public static final int EVENT_TYPE_INPUT_TEXT_REPLACE = TextEventInterpretation.TEXT_REPLACE;
  public static final int EVENT_TYPE_INPUT_TEXT_PASSWORD_ADD =
      TextEventInterpretation.TEXT_PASSWORD_ADD;
  public static final int EVENT_TYPE_INPUT_TEXT_PASSWORD_REMOVE =
      TextEventInterpretation.TEXT_PASSWORD_REMOVE;
  public static final int EVENT_TYPE_INPUT_TEXT_PASSWORD_REPLACE =
      TextEventInterpretation.TEXT_PASSWORD_REPLACE;
  public static final int EVENT_TYPE_INPUT_CHANGE_INVALID = TextEventInterpretation.CHANGE_INVALID;
  public static final int EVENT_TYPE_INPUT_SELECTION_FOCUS_EDIT_TEXT =
      TextEventInterpretation.SELECTION_FOCUS_EDIT_TEXT;
  public static final int EVENT_TYPE_INPUT_SELECTION_MOVE_CURSOR_TO_BEGINNING =
      TextEventInterpretation.SELECTION_MOVE_CURSOR_TO_BEGINNING;
  public static final int EVENT_TYPE_INPUT_SELECTION_MOVE_CURSOR_TO_END =
      TextEventInterpretation.SELECTION_MOVE_CURSOR_TO_END;
  public static final int EVENT_TYPE_INPUT_SELECTION_MOVE_CURSOR_NO_SELECTION =
      TextEventInterpretation.SELECTION_MOVE_CURSOR_NO_SELECTION;
  public static final int EVENT_TYPE_INPUT_SELECTION_MOVE_CURSOR_WITH_SELECTION =
      TextEventInterpretation.SELECTION_MOVE_CURSOR_WITH_SELECTION;
  public static final int EVENT_TYPE_INPUT_SELECTION_MOVE_CURSOR_SELECTION_CLEARED =
      TextEventInterpretation.SELECTION_MOVE_CURSOR_SELECTION_CLEARED;
  public static final int EVENT_TYPE_INPUT_SELECTION_CUT = TextEventInterpretation.SELECTION_CUT;
  public static final int EVENT_TYPE_INPUT_SELECTION_PASTE =
      TextEventInterpretation.SELECTION_PASTE;
  public static final int EVENT_TYPE_INPUT_SELECTION_TEXT_TRAVERSAL =
      TextEventInterpretation.SELECTION_TEXT_TRAVERSAL;
  public static final int EVENT_TYPE_INPUT_SELECTION_SELECT_ALL =
      TextEventInterpretation.SELECTION_SELECT_ALL;
  public static final int EVENT_TYPE_INPUT_SELECTION_SELECT_ALL_WITH_KEYBOARD =
      TextEventInterpretation.SELECTION_SELECT_ALL_WITH_KEYBOARD;
  public static final int EVENT_TYPE_INPUT_SELECTION_RESET_SELECTION =
      TextEventInterpretation.SELECTION_RESET_SELECTION;

  // Accessibility events
  public static final int EVENT_TYPE_VIEW_ACCESSIBILITY_FOCUSED =
      AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED;
  public static final int EVENT_TYPE_VIEW_FOCUSED = AccessibilityEvent.TYPE_VIEW_FOCUSED;
  public static final int EVENT_TYPE_VIEW_HOVER_ENTER = AccessibilityEvent.TYPE_VIEW_HOVER_ENTER;
  public static final int EVENT_TYPE_VIEW_CLICKED = AccessibilityEvent.TYPE_VIEW_CLICKED;
  public static final int EVENT_TYPE_VIEW_LONG_CLICKED = AccessibilityEvent.TYPE_VIEW_LONG_CLICKED;
  public static final int EVENT_TYPE_NOTIFICATION_STATE_CHANGED =
      AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED;
  public static final int EVENT_TYPE_WINDOW_CONTENT_CHANGED =
      AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
  public static final int EVENT_TYPE_VIEW_SELECTED = AccessibilityEvent.TYPE_VIEW_SELECTED;
  public static final int EVENT_TYPE_VIEW_SCROLLED = AccessibilityEvent.TYPE_VIEW_SCROLLED;
  public static final int EVENT_TYPE_ANNOUNCEMENT = AccessibilityEvent.TYPE_ANNOUNCEMENT;
  public static final int EVENT_TYPE_WINDOW_STATE_CHANGED =
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
  public static final int EVENT_TYPE_TOUCH_INTERACTION_START =
      AccessibilityEvent.TYPE_TOUCH_INTERACTION_START;
  public static final int EVENT_TYPE_TOUCH_INTERACTION_END =
      AccessibilityEvent.TYPE_TOUCH_INTERACTION_END;
  public static final int EVENT_TYPE_VIEW_TEXT_CHANGED = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED;
  public static final int EVENT_TYPE_VIEW_TEXT_SELECTION_CHANGED =
      AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED;
  public static final int EVENT_TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY =
      AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY;

  @Event
  public static int toCompositorEvent(@TextEventInterpretation.TextEvent int textEvent) {
    return textEvent;
  }

  @Event
  public static int toCompositorEvent(AccessibilityEvent event) {
    final int eventType = event.getEventType();
      if (eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
        return EVENT_TYPE_VIEW_ACCESSIBILITY_FOCUSED;
      } else if (eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
          return EVENT_TYPE_VIEW_FOCUSED;
      } else if (eventType == AccessibilityEvent.TYPE_VIEW_HOVER_ENTER) {
        return EVENT_TYPE_VIEW_HOVER_ENTER;
      } else if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
        return EVENT_TYPE_VIEW_CLICKED;
      } else if (eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
        return EVENT_TYPE_VIEW_LONG_CLICKED;
      } else if (eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
        return EVENT_TYPE_NOTIFICATION_STATE_CHANGED;
      } else if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
        return EVENT_TYPE_WINDOW_CONTENT_CHANGED;
      } else if (eventType == AccessibilityEvent.TYPE_VIEW_SELECTED) {
        return EVENT_TYPE_VIEW_SELECTED;
      } else if (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
        return EVENT_TYPE_VIEW_SCROLLED;
      } else if (eventType == AccessibilityEvent.TYPE_ANNOUNCEMENT) {
        return EVENT_TYPE_ANNOUNCEMENT;
      } else if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
        return EVENT_TYPE_WINDOW_STATE_CHANGED;
      } else if (eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) {
        return EVENT_TYPE_TOUCH_INTERACTION_START;
      } else if (eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END) {
        return EVENT_TYPE_TOUCH_INTERACTION_END;
      } else if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
        return EVENT_TYPE_VIEW_TEXT_CHANGED;
      } else if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
        return EVENT_TYPE_VIEW_TEXT_SELECTION_CHANGED;
      } else if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY) {
        return EVENT_TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY;
      } else {
        return EVENT_UNKNOWN;
      }
  }

  // IDs of the output types.
  private static final int OUTPUT_TTS_OUTPUT = 0;
  private static final int OUTPUT_TTS_QUEUE_MODE = 1;
  private static final int OUTPUT_TTS_ADD_TO_HISTORY = 2;
  private static final int OUTPUT_TTS_FORCE_FEEDBACK_EVEN_IF_AUDIO_PLAYBACK_ACTIVE = 3;
  private static final int OUTPUT_TTS_FORCE_FEEDBACK_EVEN_IF_MICROPHONE_ACTIVE = 4;
  private static final int OUTPUT_TTS_FORCE_FEEDBACK_EVEN_IF_SSB_ACTIVE = 5;
  private static final int OUTPUT_TTS_FORCE_FEEDBACK_EVEN_IF_PHONE_CALL_ACTIVE = 6;
  private static final int OUTPUT_TTS_INTERRUPT_SAME_GROUP = 7;
  private static final int OUTPUT_TTS_SKIP_DUPLICATE = 8;
  private static final int OUTPUT_TTS_CLEAR_QUEUE_GROUP = 9;
  private static final int OUTPUT_TTS_PITCH = 10;
  private static final int OUTPUT_ADVANCE_CONTINUOUS_READING = 11;
  private static final int OUTPUT_PREVENT_DEVICE_SLEEP = 12;
  private static final int OUTPUT_REFRESH_SOURCE_NODE = 13;
  private static final int OUTPUT_HAPTIC = 14;
  private static final int OUTPUT_EARCON = 15;
  private static final int OUTPUT_EARCON_RATE = 16;
  private static final int OUTPUT_EARCON_VOLUME = 17;
  private static final int OUTPUT_TTS_FORCE_FEEDBACK = 18;

  // IDs of the enum types.
  private static final int ENUM_TTS_QUEUE_MODE = 0;
  private static final int ENUM_TTS_QUEUE_GROUP = 1;
  static final int ENUM_ROLE = 2;
  static final int ENUM_LIVE_REGION = 3;
  static final int ENUM_WINDOW_TYPE = 4;
  static final int ENUM_VERBOSITY_DESCRIPTION_ORDER = 5;

  static final int ENUM_RANGE_INFO_TYPE = 6;
  static final int RANGE_INFO_UNDEFINED = -1;

  // Enum values
  private static final int QUEUE_MODE_INTERRUPTIBLE_IF_LONG = 0x40000001;

  // Constant parameters
  private static final int VERBOSE_UTTERANCE_THRESHOLD_CHARACTERS = 50;

  /** IDs of description orders in verbosity setting. */
  @IntDef({
    DESC_ORDER_ROLE_NAME_STATE_POSITION,
    DESC_ORDER_STATE_NAME_ROLE_POSITION,
    DESC_ORDER_NAME_ROLE_STATE_POSITION
  })
  @Retention(RetentionPolicy.SOURCE)
  public @interface DescriptionOrder {}

  public static final int DESC_ORDER_ROLE_NAME_STATE_POSITION = 0;
  public static final int DESC_ORDER_STATE_NAME_ROLE_POSITION = 1;
  public static final int DESC_ORDER_NAME_ROLE_STATE_POSITION = 2;

  /////////////////////////////////////////////////////////////////////////////////
  // Member variables

  private final @Nullable SpeechController speechController;

  /**
   * A callback to speak text via some unknown speech service. Only 1 of speaker and
   * speechController should be set, to avoid speaking twice.
   */
  private @Nullable Speaker speaker;

  private final Context mContext;

  private ParseTree mParseTree;
  private final VariablesFactory mVariablesFactory;

  /////////////////////////////////////////////////////////////////////////////////
  // Inner classes

  /** Callback interface for talkback-pipeline to receive async speech feedback. */
  public interface Speaker {
    void speak(CharSequence text, @Nullable EventId eventId, SpeakOptions options);
  }

  /////////////////////////////////////////////////////////////////////////////////
  // Construction methods

  public Compositor(
      Context context,
      @Nullable SpeechController speechController,
      @Nullable ImageContents imageContents,
      GlobalVariables globalVariables,
      @Flavor int flavor) {
    this.speechController = speechController;
    mVariablesFactory = new VariablesFactory(context, globalVariables, imageContents);
    mContext = context;

    long startTime = SystemClock.uptimeMillis();
    mParseTree = refreshParseTree(mContext, mVariablesFactory, flavor);
    long endTime = SystemClock.uptimeMillis();
    LogUtils.i(
        TAG,
        "ParseTree built for compositor %s in %d ms",
        getFlavorName(flavor),
        endTime - startTime);
  }

  public void setNodeMenuProvider(@Nullable NodeMenuProvider nodeMenuProvider) {
    mVariablesFactory.setNodeMenuProvider(nodeMenuProvider);
  }

  public void setSpeaker(Speaker speaker) {
    this.speaker = speaker;
  }

  // Gets the user preferred locale changed using language switcher.
  public @Nullable Locale getUserPreferredLanguage() {
    return mVariablesFactory.getUserPreferredLocale();
  }

  // Sets the user preferred locale changed using language switcher.
  public void setUserPreferredLanguage(Locale locale) {
    mVariablesFactory.setUserPreferredLocale(locale);
  }

  /////////////////////////////////////////////////////////////////////////////////
  // Feedback mapping methods

  /**
   * Handles an event that has no meta-data associated with it.
   *
   * @param event Type of event that has occurred.
   * @param runnable Run when TTS output has completed
   */
  public void handleEventWithCompletionHandler(
      @Event int event, EventId eventId, SpeechController.UtteranceCompleteRunnable runnable) {
    HandleEventOptions options = new HandleEventOptions().onComplete(runnable);
    handleEvent(event, eventId, mVariablesFactory.getDefaultDelegate(), options);
  }

  /**
   * Handles an event that has no meta-data associated with it.
   *
   * @param event Type of event that has occurred.
   * @param eventId ID of the event used for performance monitoring.
   */
  public void handleEvent(@Event int event, @Nullable EventId eventId) {
    ParseTree.VariableDelegate variables = mVariablesFactory.getDefaultDelegate();
    HandleEventOptions options = new HandleEventOptions();
    handleEvent(event, eventId, variables, options);
  }

  /**
   * Handles an event that has a node associated.
   *
   * @param event Type of event that has occurred.
   * @param event Type of event that has occurred.
   * @param eventId ID of the event used for performance monitoring.
   */
  public void handleEvent(
      @Event int event, AccessibilityNodeInfoCompat node, @Nullable EventId eventId) {
    ParseTree.VariableDelegate variables =
        mVariablesFactory.createLocalVariableDelegate(null, node, null);
    HandleEventOptions options = new HandleEventOptions();
    handleEvent(event, eventId, variables, options);
  }

  /** Handles an internally-generated accessibility event. */
  public void handleEvent(@Nullable EventId eventId, EventInterpretation eventInterpretation) {
    ParseTree.VariableDelegate variables =
        mVariablesFactory.createLocalVariableDelegate(null, null, eventInterpretation);
    HandleEventOptions options = new HandleEventOptions().interpretation(eventInterpretation);
    handleEvent(eventInterpretation.getEvent(), eventId, variables, options);
  }

  /**
   * Handles an internally-generated accessibility event.
   *
   * @param source Source of the event that has occurred
   * @param eventId ID of the event, used for performance monitoring
   * @param eventInterpretation Information about the event
   */
  public void handleEvent(
      @Nullable AccessibilityNodeInfoCompat source,
      @Nullable EventId eventId,
      EventInterpretation eventInterpretation) {
    ParseTree.VariableDelegate variables =
        mVariablesFactory.createLocalVariableDelegate(null, source, eventInterpretation);
    HandleEventOptions options =
        new HandleEventOptions().source(source).interpretation(eventInterpretation);
    handleEvent(eventInterpretation.getEvent(), eventId, variables, options);
  }

  /** Handles a standard AccessibilityEvent */
  public void handleEvent(
      AccessibilityEvent event, @Nullable EventId eventId, EventInterpretation eventInterpreted) {

    final AccessibilityRecordCompat record = AccessibilityEventCompat.asRecord(event);
    @Event int eventType = eventInterpreted.getEvent();

    // TODO: getSource may cost time
    // Allocate source node & delegate.
    AccessibilityNodeInfoCompat sourceNode = record.getSource();
    ParseTree.VariableDelegate delegate =
        mVariablesFactory.createLocalVariableDelegate(event, sourceNode, eventInterpreted);

    // Compute speech and speech flags.
    HandleEventOptions options =
        new HandleEventOptions().object(event).interpretation(eventInterpreted).source(sourceNode);
    handleEvent(eventType, eventId, delegate, options);
  }

  private void handleEvent(
      int event,
      @Nullable EventId eventId,
      ParseTree.VariableDelegate delegate,
      HandleEventOptions options) {

    // Extract options.
    @Nullable AccessibilityEvent eventObject = options.eventObject;
    @Nullable EventInterpretation eventInterpretation = options.eventInterpretation;
    if (eventInterpretation != null) {
      LogUtils.v(TAG, "eventInterpretation= %s", eventInterpretation);
    }
    @Nullable AccessibilityNodeInfoCompat sourceNode = options.sourceNode;
    @Nullable UtteranceCompleteRunnable runnable = options.onCompleteRunnable;

    // Refresh source node, and re-create variable delegate using fresh source node.
    if (sourceNode != null) {
      boolean refreshSource =
          mParseTree.parseEventToBool(
              event, OUTPUT_REFRESH_SOURCE_NODE, false /* default */, delegate);
      if (refreshSource) {
        AccessibilityNodeInfoCompat newSourceNode =
            AccessibilityNodeInfoUtils.refreshNode(sourceNode);
        delegate =
            mVariablesFactory.createLocalVariableDelegate(
                eventObject, newSourceNode, eventInterpretation);
      }
    }

    // Compose earcons.
    SpeakOptions speakOptions = null;
    int earcon = mParseTree.parseEventToInteger(event, OUTPUT_EARCON, -1, delegate);
    if (earcon != -1) {
      if (speakOptions == null) {
        speakOptions = SpeakOptions.create();
      }
      Set<Integer> earcons = new HashSet<>();
      earcons.add(earcon);
      speakOptions.setEarcons(earcons);

      Bundle nonSpeechParams = new Bundle();
      double rate = mParseTree.parseEventToNumber(event, OUTPUT_EARCON_RATE, 1.0, delegate);
      if (rate != 1.0) {
        nonSpeechParams.putFloat(Utterance.KEY_METADATA_EARCON_RATE, (float) rate);
      }
      double volume = mParseTree.parseEventToNumber(event, OUTPUT_EARCON_RATE, 1.0, delegate);
      if (volume != 1.0) {
        nonSpeechParams.putFloat(Utterance.KEY_METADATA_EARCON_VOLUME, (float) volume);
      }
      speakOptions.setNonSpeechParams(nonSpeechParams);
    }

    // Compose haptics.
    int haptic = mParseTree.parseEventToInteger(event, OUTPUT_HAPTIC, -1, delegate);
    if (haptic != -1) {
      if (speakOptions == null) {
        speakOptions = SpeakOptions.create();
      }
      Set<Integer> haptics = new HashSet<>();
      haptics.add(haptic);
      speakOptions.setHaptics(haptics);
    }

    // FLAG_ADVANCED_CONTINUOUS_READING is used for "read from top". Ensure that the flag is set
    // correctly in SpeakOptions regardless of the speech/haptics/earcon feedback. So that the
    // "read from top" will not stop at focusable node with no feedback.
    if (hasFlagAdvancedContinuousReading(event, delegate)) {
      if (speakOptions == null) {
        speakOptions = SpeakOptions.create();
      }
      speakOptions.mFlags |= FeedbackItem.FLAG_ADVANCE_CONTINUOUS_READING;
    }

    // Compose speech, and speech parameters.
    CharSequence ttsOutput = mParseTree.parseEventToString(event, OUTPUT_TTS_OUTPUT, delegate);
    if (!TextUtils.isEmpty(ttsOutput)) {
      // Cleans up the TTS output if it is just 1 character long. This will announce single
      // symbols correctly.
      // TODO: Think about a unified clean up strategy instead of calling clean ups at
      // various places in the code.
      ttsOutput = SpeechCleanupUtils.cleanUp(mContext, ttsOutput);
      // Compute queueing mode.
      int queueMode =
          mParseTree.parseEventToEnum(
              event, OUTPUT_TTS_QUEUE_MODE, SpeechController.QUEUE_MODE_INTERRUPT, delegate);
      if (eventObject != null
          && (eventObject.getEventType() == AccessibilityEvent.TYPE_ANNOUNCEMENT)
          && "com.google.android.apps.nexuslauncher".equals(eventObject.getPackageName())) {
        // Launcher uses TYPE_ANNOUNCEMENT to convey the "Item moved" indication for the custom
        // action [Move item]. Then Launcher renders animation before Window Content Change happens.
        // If animation is off, the announcement would  be interrupted by the subsequent re-focus
        // event.
        // TODO Plan to cope with general case of TYPE_ANNOUNCEMENT. 1. Replace the tts
        // queue mode with interruptible_if_long, 2. Reduce the utterance threshold length. 3. May
        // need an escape for accumulated announcements.
        queueMode = QUEUE_MODE_INTERRUPTIBLE_IF_LONG;
      }
      if (queueMode == QUEUE_MODE_INTERRUPTIBLE_IF_LONG) {
        queueMode =
            (ttsOutput.length() <= VERBOSE_UTTERANCE_THRESHOLD_CHARACTERS)
                ? QUEUE_MODE_UNINTERRUPTIBLE_BY_NEW_SPEECH
                : QUEUE_MODE_INTERRUPT;
      }

      // Compose queue group to clear.
      int clearQueueGroup =
          mParseTree.parseEventToEnum(
              event,
              OUTPUT_TTS_CLEAR_QUEUE_GROUP,
              SpeechController.UTTERANCE_GROUP_DEFAULT,
              delegate);

      // Compose other speech flags/parameters.
      int flags = getSpeechFlags(event, clearQueueGroup, delegate);
      double speechPitch = mParseTree.parseEventToNumber(event, OUTPUT_TTS_PITCH, 1.0, delegate);
      Bundle speechParams = new Bundle();
      speechParams.putFloat(SpeechParam.PITCH, (float) speechPitch);

      // Output feedback: speech, haptics, earcons.
      if (speakOptions == null) {
        speakOptions = SpeakOptions.create();
      }
      speakOptions
          .setQueueMode(queueMode)
          .setSpeechParams(speechParams)
          .setUtteranceGroup(clearQueueGroup)
          .setCompletedAction(runnable);
      speakOptions.mFlags |= flags;
      speak(ttsOutput, eventId, speakOptions);
    } else {
      if (speakOptions != null) {
        speakOptions.mFlags |= FeedbackItem.FLAG_NO_SPEECH;
        // TODO: Return feedback as output when feedback-mappers are separated from
        // asynchronous event interpreters.
        speak("", eventId, speakOptions);
      }
      if (runnable != null) {
        runnable.run(SpeechController.STATUS_NOT_SPOKEN);
      }
    }
  }

  public @Nullable String parseTTSText(
      @Nullable AccessibilityNodeInfoCompat source,
      int event,
      EventInterpretation eventInterpretation) {
    if (mParseTree == null) {
      return null;
    }
    ParseTree.VariableDelegate delegate =
        mVariablesFactory.createLocalVariableDelegate(
            /* event= */ null, source, eventInterpretation);

    CharSequence ttsText = mParseTree.parseEventToString(event, OUTPUT_TTS_OUTPUT, delegate);

    if (ttsText == null) {
      return null;
    }
    return ttsText.toString();
  }

  private static class HandleEventOptions {
    public @Nullable AccessibilityEvent eventObject;
    public @Nullable EventInterpretation eventInterpretation;
    public @Nullable AccessibilityNodeInfoCompat sourceNode;
    public @Nullable UtteranceCompleteRunnable onCompleteRunnable;

    public HandleEventOptions object(AccessibilityEvent eventObjArg) {
      eventObject = eventObjArg;
      return this;
    }

    public HandleEventOptions interpretation(EventInterpretation eventInterpArg) {
      eventInterpretation = eventInterpArg;
      return this;
    }

    public HandleEventOptions source(@Nullable AccessibilityNodeInfoCompat sourceArg) {
      sourceNode = sourceArg;
      return this;
    }

    public HandleEventOptions onComplete(UtteranceCompleteRunnable runnableArg) {
      onCompleteRunnable = runnableArg;
      return this;
    }
  }

  private void speak(CharSequence ttsOutput, @Nullable EventId eventId, SpeakOptions speakOptions) {
    if (speechController != null) {
      speechController.speak(ttsOutput, eventId, speakOptions);
    }
    if (speaker != null) {
      speaker.speak(ttsOutput, eventId, speakOptions);
    }
  }

  private boolean hasFlagAdvancedContinuousReading(
      int event, ParseTree.VariableDelegate variables) {
    return mParseTree.parseEventToBool(event, OUTPUT_ADVANCE_CONTINUOUS_READING, false, variables);
  }

  /**
   * Gets speech flag mask for the event. <strong>Note:</strong> This method doesn't handle {@link
   * FeedbackItem#FLAG_ADVANCE_CONTINUOUS_READING}, which should be handled after calling {@link
   * #hasFlagAdvancedContinuousReading}.
   */
  private int getSpeechFlags(int event, int clearQueueGroup, ParseTree.VariableDelegate variables) {
    int flags = 0;
    if (!mParseTree.parseEventToBool(event, OUTPUT_TTS_ADD_TO_HISTORY, false, variables)) {
      flags = flags | FeedbackItem.FLAG_NO_HISTORY;
    }
    if (mParseTree.parseEventToBool(event, OUTPUT_TTS_FORCE_FEEDBACK, false, variables)) {
      flags = flags | FeedbackItem.FLAG_FORCE_FEEDBACK;
    }
    if (mParseTree.parseEventToBool(
        event, OUTPUT_TTS_FORCE_FEEDBACK_EVEN_IF_AUDIO_PLAYBACK_ACTIVE, false, variables)) {
      flags = flags | FeedbackItem.FLAG_FORCE_FEEDBACK_EVEN_IF_AUDIO_PLAYBACK_ACTIVE;
    }
    if (mParseTree.parseEventToBool(
        event, OUTPUT_TTS_FORCE_FEEDBACK_EVEN_IF_MICROPHONE_ACTIVE, false, variables)) {
      flags = flags | FeedbackItem.FLAG_FORCE_FEEDBACK_EVEN_IF_MICROPHONE_ACTIVE;
    }
    if (mParseTree.parseEventToBool(
        event, OUTPUT_TTS_FORCE_FEEDBACK_EVEN_IF_SSB_ACTIVE, false, variables)) {
      flags = flags | FeedbackItem.FLAG_FORCE_FEEDBACK_EVEN_IF_SSB_ACTIVE;
    }
    if (mParseTree.parseEventToBool(
        event, OUTPUT_TTS_FORCE_FEEDBACK_EVEN_IF_PHONE_CALL_ACTIVE, true, variables)) {
      flags = flags | FeedbackItem.FLAG_FORCE_FEEDBACK_EVEN_IF_PHONE_CALL_ACTIVE;
    }
    if (mParseTree.parseEventToBool(event, OUTPUT_TTS_SKIP_DUPLICATE, false, variables)) {
      flags = flags | FeedbackItem.FLAG_SKIP_DUPLICATE;
    }
    if (clearQueueGroup != SpeechController.UTTERANCE_GROUP_DEFAULT) {
      flags = flags | FeedbackItem.FLAG_CLEAR_QUEUED_UTTERANCES_WITH_SAME_UTTERANCE_GROUP;
    }
    if (mParseTree.parseEventToBool(event, OUTPUT_TTS_INTERRUPT_SAME_GROUP, false, variables)) {
      flags = flags | FeedbackItem.FLAG_INTERRUPT_CURRENT_UTTERANCE_WITH_SAME_UTTERANCE_GROUP;
    }
    if (mParseTree.parseEventToBool(event, OUTPUT_PREVENT_DEVICE_SLEEP, false, variables)) {
      flags = flags | FeedbackItem.FLAG_NO_DEVICE_SLEEP;
    }
    return flags;
  }

  private static ParseTree refreshParseTree(
      Context context, VariablesFactory variablesFactory, @Flavor int flavor) {
    ParseTree parseTree = new ParseTree(context.getResources(), context.getPackageName());

    declareEnums(parseTree);
    declareEvents(parseTree);
    variablesFactory.declareVariables(parseTree);

    try {
      parseTree.mergeTree(JsonUtils.readFromRawFile(context, R.raw.compositor));
    } catch (Exception e) {
      throw new IllegalStateException(e.toString());
    }

    parseTree.build();

    return parseTree;
  }

  private static void declareEnums(ParseTree parseTree) {
    Map<Integer, String> queueModes = new HashMap<>();
    queueModes.put(SpeechController.QUEUE_MODE_INTERRUPT, "interrupt");
    queueModes.put(SpeechController.QUEUE_MODE_QUEUE, "queue");
    queueModes.put(SpeechController.QUEUE_MODE_UNINTERRUPTIBLE_BY_NEW_SPEECH, "uninterruptible");
    queueModes.put(SpeechController.QUEUE_MODE_CAN_IGNORE_INTERRUPTS, "ignoreInterrupts");
    queueModes.put(
        SpeechController.QUEUE_MODE_UNINTERRUPTIBLE_BY_NEW_SPEECH_CAN_IGNORE_INTERRUPTS,
        "uninterruptibleAndIgnoreInterrupts");
    queueModes.put(SpeechController.QUEUE_MODE_FLUSH_ALL, "flush");
    queueModes.put(QUEUE_MODE_INTERRUPTIBLE_IF_LONG, "interruptible_if_long");

    parseTree.addEnum(ENUM_TTS_QUEUE_MODE, queueModes);

    Map<Integer, String> speechQueueGroups = new HashMap<>();
    speechQueueGroups.put(SpeechController.UTTERANCE_GROUP_DEFAULT, "default");
    speechQueueGroups.put(SpeechController.UTTERANCE_GROUP_PROGRESS_BAR_PROGRESS, "progress_bar");
    speechQueueGroups.put(SpeechController.UTTERANCE_GROUP_SEEK_PROGRESS, "seek_progress");
    speechQueueGroups.put(SpeechController.UTTERANCE_GROUP_TEXT_SELECTION, "text_selection");
    speechQueueGroups.put(
        SpeechController.UTTERANCE_GROUP_SCREEN_MAGNIFICATION, "screen_magnification");

    parseTree.addEnum(ENUM_TTS_QUEUE_GROUP, speechQueueGroups);

    Map<Integer, String> roles = new HashMap<>();
    roles.put(Role.ROLE_NONE, "none");
    roles.put(Role.ROLE_BUTTON, "button");
    roles.put(Role.ROLE_CHECK_BOX, "check_box");
    roles.put(Role.ROLE_DROP_DOWN_LIST, "drop_down_list");
    roles.put(Role.ROLE_EDIT_TEXT, "edit_text");
    roles.put(Role.ROLE_GRID, "grid");
    roles.put(Role.ROLE_IMAGE, "image");
    roles.put(Role.ROLE_IMAGE_BUTTON, "image_button");
    roles.put(Role.ROLE_LIST, "list");
    roles.put(Role.ROLE_PAGER, "pager");
    roles.put(Role.ROLE_PROGRESS_BAR, "progress_bar");
    roles.put(Role.ROLE_RADIO_BUTTON, "radio_button");
    roles.put(Role.ROLE_SEEK_CONTROL, "seek_control");
    roles.put(Role.ROLE_SWITCH, "switch");
    roles.put(Role.ROLE_TAB_BAR, "tab_bar");
    roles.put(Role.ROLE_TOGGLE_BUTTON, "toggle_button");
    roles.put(Role.ROLE_VIEW_GROUP, "view_group");
    roles.put(Role.ROLE_WEB_VIEW, "web_view");
    roles.put(Role.ROLE_CHECKED_TEXT_VIEW, "checked_text_view");
    roles.put(Role.ROLE_ACTION_BAR_TAB, "action_bar_tab");
    roles.put(Role.ROLE_DRAWER_LAYOUT, "drawer_layout");
    roles.put(Role.ROLE_SLIDING_DRAWER, "sliding_drawer");
    roles.put(Role.ROLE_ICON_MENU, "icon_menu");
    roles.put(Role.ROLE_TOAST, "toast");
    roles.put(Role.ROLE_ALERT_DIALOG, "alert_dialog");
    roles.put(Role.ROLE_DATE_PICKER_DIALOG, "date_picker_dialog");
    roles.put(Role.ROLE_TIME_PICKER_DIALOG, "time_picker_dialog");
    roles.put(Role.ROLE_DATE_PICKER, "date_picker");
    roles.put(Role.ROLE_TIME_PICKER, "time_picker");
    roles.put(Role.ROLE_NUMBER_PICKER, "number_picker");
    roles.put(Role.ROLE_SCROLL_VIEW, "scroll_view");
    roles.put(Role.ROLE_HORIZONTAL_SCROLL_VIEW, "horizontal_scroll_view");
    roles.put(Role.ROLE_TEXT_ENTRY_KEY, "text_entry_key");

    parseTree.addEnum(ENUM_ROLE, roles);

    Map<Integer, String> liveRegions = new HashMap<>();
    liveRegions.put(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE, "assertive");
    liveRegions.put(View.ACCESSIBILITY_LIVE_REGION_POLITE, "polite");
    liveRegions.put(View.ACCESSIBILITY_LIVE_REGION_NONE, "none");

    parseTree.addEnum(ENUM_LIVE_REGION, liveRegions);

    Map<Integer, String> windowTypes = new HashMap<>();
    windowTypes.put(AccessibilityNodeInfoUtils.WINDOW_TYPE_NONE, "none");
    windowTypes.put(
        AccessibilityNodeInfoUtils.WINDOW_TYPE_PICTURE_IN_PICTURE, "picture_in_picture");
    windowTypes.put(
        AccessibilityWindowInfoCompat.TYPE_ACCESSIBILITY_OVERLAY, "accessibility_overlay");
    windowTypes.put(AccessibilityWindowInfoCompat.TYPE_APPLICATION, "application");
    windowTypes.put(AccessibilityWindowInfoCompat.TYPE_INPUT_METHOD, "input_method");
    windowTypes.put(AccessibilityWindowInfoCompat.TYPE_SYSTEM, "system");
    windowTypes.put(AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, "split_screen_divider");

    parseTree.addEnum(ENUM_WINDOW_TYPE, windowTypes);

    Map<Integer, String> verbosityDescOrderValues = new HashMap<>();
    verbosityDescOrderValues.put(DESC_ORDER_ROLE_NAME_STATE_POSITION, "RoleNameStatePosition");
    verbosityDescOrderValues.put(DESC_ORDER_STATE_NAME_ROLE_POSITION, "StateNameRolePosition");
    verbosityDescOrderValues.put(DESC_ORDER_NAME_ROLE_STATE_POSITION, "NameRoleStatePosition");
    parseTree.addEnum(ENUM_VERBOSITY_DESCRIPTION_ORDER, verbosityDescOrderValues);

    Map<Integer, String> rangeInfoTypes = new HashMap<>();
    rangeInfoTypes.put(RangeInfo.RANGE_TYPE_INT, "int");
    rangeInfoTypes.put(RangeInfo.RANGE_TYPE_FLOAT, "float");
    rangeInfoTypes.put(RangeInfo.RANGE_TYPE_PERCENT, "percent");
    rangeInfoTypes.put(RANGE_INFO_UNDEFINED, "undefined");
    parseTree.addEnum(ENUM_RANGE_INFO_TYPE, rangeInfoTypes);
  }

  private static void declareEvents(ParseTree parseTree) {
    // Service events.
    parseTree.addEvent("SpokenFeedbackOn", EVENT_SPOKEN_FEEDBACK_ON);
    parseTree.addEvent("SpokenFeedbackDisabled", EVENT_SPOKEN_FEEDBACK_DISABLED);
    parseTree.addEvent("CapsLockOn", EVENT_CAPS_LOCK_ON);
    parseTree.addEvent("CapsLockOff", EVENT_CAPS_LOCK_OFF);
    parseTree.addEvent("NumLockOn", EVENT_NUM_LOCK_ON);
    parseTree.addEvent("NumLockOff", EVENT_NUM_LOCK_OFF);
    parseTree.addEvent("ScrollLockOn", EVENT_SCROLL_LOCK_ON);
    parseTree.addEvent("ScrollLockOff", EVENT_SCROLL_LOCK_OFF);
    parseTree.addEvent("OrientationPortrait", EVENT_ORIENTATION_PORTRAIT);
    parseTree.addEvent("OrientationLandscape", EVENT_ORIENTATION_LANDSCAPE);
    parseTree.addEvent("Hint", EVENT_SPEAK_HINT);
    parseTree.addEvent("MagnificationChanged", EVENT_MAGNIFICATION_CHANGED);

    // Accessibility events.
    parseTree.addEvent(
        "TYPE_VIEW_ACCESSIBILITY_FOCUSED", AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
    parseTree.addEvent("TYPE_VIEW_FOCUSED", AccessibilityEvent.TYPE_VIEW_FOCUSED);
    parseTree.addEvent("TYPE_VIEW_HOVER_ENTER", AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);
    parseTree.addEvent("TYPE_VIEW_CLICKED", AccessibilityEvent.TYPE_VIEW_CLICKED);
    parseTree.addEvent("TYPE_VIEW_LONG_CLICKED", AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
    parseTree.addEvent(
        "TYPE_NOTIFICATION_STATE_CHANGED", TYPE_NOTIFICATION_STATE_CHANGED);
    parseTree.addEvent(
        "TYPE_WINDOW_CONTENT_CHANGED", AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    parseTree.addEvent("TYPE_VIEW_SELECTED", AccessibilityEvent.TYPE_VIEW_SELECTED);
    parseTree.addEvent("TYPE_VIEW_SCROLLED", AccessibilityEvent.TYPE_VIEW_SCROLLED);
    parseTree.addEvent("TYPE_ANNOUNCEMENT", AccessibilityEvent.TYPE_ANNOUNCEMENT);
    parseTree.addEvent("TYPE_WINDOW_STATE_CHANGED", AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);

    // Interpreted events.
    parseTree.addEvent("EVENT_TYPE_INPUT_TEXT_CLEAR", EVENT_TYPE_INPUT_TEXT_CLEAR);
    parseTree.addEvent("EVENT_TYPE_INPUT_TEXT_REMOVE", EVENT_TYPE_INPUT_TEXT_REMOVE);
    parseTree.addEvent("EVENT_TYPE_INPUT_TEXT_ADD", EVENT_TYPE_INPUT_TEXT_ADD);
    parseTree.addEvent("EVENT_TYPE_INPUT_TEXT_REPLACE", EVENT_TYPE_INPUT_TEXT_REPLACE);
    parseTree.addEvent("EVENT_TYPE_INPUT_TEXT_PASSWORD_ADD", EVENT_TYPE_INPUT_TEXT_PASSWORD_ADD);
    parseTree.addEvent(
        "EVENT_TYPE_INPUT_TEXT_PASSWORD_REMOVE", EVENT_TYPE_INPUT_TEXT_PASSWORD_REMOVE);
    parseTree.addEvent(
        "EVENT_TYPE_INPUT_TEXT_PASSWORD_REPLACE", EVENT_TYPE_INPUT_TEXT_PASSWORD_REPLACE);
    parseTree.addEvent("EVENT_TYPE_INPUT_CHANGE_INVALID", EVENT_TYPE_INPUT_CHANGE_INVALID);
    parseTree.addEvent(
        "EVENT_TYPE_INPUT_SELECTION_FOCUS_EDIT_TEXT", EVENT_TYPE_INPUT_SELECTION_FOCUS_EDIT_TEXT);
    parseTree.addEvent(
        "EVENT_TYPE_INPUT_SELECTION_MOVE_CURSOR_TO_BEGINNING",
        EVENT_TYPE_INPUT_SELECTION_MOVE_CURSOR_TO_BEGINNING);
    parseTree.addEvent(
        "EVENT_TYPE_INPUT_SELECTION_MOVE_CURSOR_TO_END",
        EVENT_TYPE_INPUT_SELECTION_MOVE_CURSOR_TO_END);
    parseTree.addEvent(
        "EVENT_TYPE_INPUT_SELECTION_MOVE_CURSOR_NO_SELECTION",
        EVENT_TYPE_INPUT_SELECTION_MOVE_CURSOR_NO_SELECTION);
    parseTree.addEvent(
        "EVENT_TYPE_INPUT_SELECTION_MOVE_CURSOR_WITH_SELECTION",
        EVENT_TYPE_INPUT_SELECTION_MOVE_CURSOR_WITH_SELECTION);
    parseTree.addEvent(
        "EVENT_TYPE_INPUT_SELECTION_MOVE_CURSOR_SELECTION_CLEARED",
        EVENT_TYPE_INPUT_SELECTION_MOVE_CURSOR_SELECTION_CLEARED);
    parseTree.addEvent("EVENT_TYPE_INPUT_SELECTION_CUT", EVENT_TYPE_INPUT_SELECTION_CUT);
    parseTree.addEvent("EVENT_TYPE_INPUT_SELECTION_PASTE", EVENT_TYPE_INPUT_SELECTION_PASTE);
    parseTree.addEvent(
        "EVENT_TYPE_INPUT_SELECTION_TEXT_TRAVERSAL", EVENT_TYPE_INPUT_SELECTION_TEXT_TRAVERSAL);
    parseTree.addEvent(
        "EVENT_TYPE_INPUT_SELECTION_SELECT_ALL", EVENT_TYPE_INPUT_SELECTION_SELECT_ALL);
    parseTree.addEvent(
        "EVENT_TYPE_INPUT_SELECTION_SELECT_ALL_WITH_KEYBOARD",
        EVENT_TYPE_INPUT_SELECTION_SELECT_ALL_WITH_KEYBOARD);
    parseTree.addEvent("EVENT_SCROLL_POSITION", EVENT_SCROLL_POSITION);
    parseTree.addEvent("EVENT_INPUT_DESCRIBE_NODE", EVENT_INPUT_DESCRIBE_NODE);

    // Outputs.
    parseTree.addStringOutput("ttsOutput", OUTPUT_TTS_OUTPUT);
    parseTree.addEnumOutput("ttsQueueMode", OUTPUT_TTS_QUEUE_MODE, ENUM_TTS_QUEUE_MODE);
    parseTree.addEnumOutput(
        "ttsClearQueueGroup", OUTPUT_TTS_CLEAR_QUEUE_GROUP, ENUM_TTS_QUEUE_GROUP);
    parseTree.addBooleanOutput("ttsInterruptSameGroup", OUTPUT_TTS_INTERRUPT_SAME_GROUP);
    parseTree.addBooleanOutput("ttsSkipDuplicate", OUTPUT_TTS_SKIP_DUPLICATE);
    parseTree.addBooleanOutput("ttsAddToHistory", OUTPUT_TTS_ADD_TO_HISTORY);
    parseTree.addBooleanOutput("ttsForceFeedback", OUTPUT_TTS_FORCE_FEEDBACK);
    parseTree.addBooleanOutput(
        "ttsForceFeedbackEvenIfAudioPlaybackActive",
        OUTPUT_TTS_FORCE_FEEDBACK_EVEN_IF_AUDIO_PLAYBACK_ACTIVE);
    parseTree.addBooleanOutput(
        "ttsForceFeedbackEvenIfMicrophoneActive",
        OUTPUT_TTS_FORCE_FEEDBACK_EVEN_IF_MICROPHONE_ACTIVE);
    parseTree.addBooleanOutput(
        "ttsForceFeedbackEvenIfSsbActive", OUTPUT_TTS_FORCE_FEEDBACK_EVEN_IF_SSB_ACTIVE);
    parseTree.addBooleanOutput(
        "ttsForceFeedbackEvenIfPhoneCallActive",
        OUTPUT_TTS_FORCE_FEEDBACK_EVEN_IF_PHONE_CALL_ACTIVE);
    parseTree.addNumberOutput("ttsPitch", OUTPUT_TTS_PITCH);
    parseTree.addBooleanOutput("advanceContinuousReading", OUTPUT_ADVANCE_CONTINUOUS_READING);
    parseTree.addBooleanOutput("preventDeviceSleep", OUTPUT_PREVENT_DEVICE_SLEEP);
    parseTree.addBooleanOutput("refreshSourceNode", OUTPUT_REFRESH_SOURCE_NODE);
    parseTree.addIntegerOutput("haptic", OUTPUT_HAPTIC);
    parseTree.addIntegerOutput("earcon", OUTPUT_EARCON);
    parseTree.addNumberOutput("earcon_rate", OUTPUT_EARCON_RATE);
    parseTree.addNumberOutput("earcon_volume", OUTPUT_EARCON_VOLUME);
  }

  ////////////////////////////////////////////////////////////////////////////////////////
  // Methods for logging

  public static String eventTypeToString(int eventType) {
    switch (eventType) {
      case EVENT_UNKNOWN:
        return "EVENT_UNKNOWN";
      case EVENT_SPOKEN_FEEDBACK_ON:
        return "EVENT_SPOKEN_FEEDBACK_ON";
      case EVENT_SPOKEN_FEEDBACK_DISABLED:
        return "EVENT_SPOKEN_FEEDBACK_DISABLED";
      case EVENT_CAPS_LOCK_ON:
        return "EVENT_CAPS_LOCK_ON";
      case EVENT_CAPS_LOCK_OFF:
        return "EVENT_CAPS_LOCK_OFF";
      case EVENT_NUM_LOCK_ON:
        return "EVENT_NUM_LOCK_ON";
      case EVENT_NUM_LOCK_OFF:
        return "EVENT_NUM_LOCK_OFF";
      case EVENT_SCROLL_LOCK_ON:
        return "EVENT_SCROLL_LOCK_ON";
      case EVENT_SCROLL_LOCK_OFF:
        return "EVENT_SCROLL_LOCK_OFF";
      case EVENT_ORIENTATION_PORTRAIT:
        return "EVENT_ORIENTATION_PORTRAIT";
      case EVENT_ORIENTATION_LANDSCAPE:
        return "EVENT_ORIENTATION_LANDSCAPE";
      case EVENT_SPEAK_HINT:
        return "EVENT_SPEAK_HINT";
      case EVENT_SCROLL_POSITION:
        return "EVENT_SCROLL_POSITION";
      case EVENT_INPUT_DESCRIBE_NODE:
        return "EVENT_INPUT_DESCRIBE_NODE";
      case EVENT_MAGNIFICATION_CHANGED:
        return "EVENT_MAGNIFICATION_CHANGED";
      case EVENT_TYPE_INPUT_TEXT_CLEAR:
        return "EVENT_TYPE_INPUT_TEXT_CLEAR";
      case EVENT_TYPE_INPUT_TEXT_REMOVE:
        return "EVENT_TYPE_INPUT_TEXT_REMOVE";
      case EVENT_TYPE_INPUT_TEXT_ADD:
        return "EVENT_TYPE_INPUT_TEXT_ADD";
      case EVENT_TYPE_INPUT_TEXT_REPLACE:
        return "EVENT_TYPE_INPUT_TEXT_REPLACE";
      case EVENT_TYPE_INPUT_TEXT_PASSWORD_ADD:
        return "EVENT_TYPE_INPUT_TEXT_PASSWORD_ADD";
      case EVENT_TYPE_INPUT_TEXT_PASSWORD_REMOVE:
        return "EVENT_TYPE_INPUT_TEXT_PASSWORD_REMOVE";
      case EVENT_TYPE_INPUT_TEXT_PASSWORD_REPLACE:
        return "EVENT_TYPE_INPUT_TEXT_PASSWORD_REPLACE";
      case EVENT_TYPE_INPUT_CHANGE_INVALID:
        return "EVENT_TYPE_INPUT_CHANGE_INVALID";
      case EVENT_TYPE_INPUT_SELECTION_FOCUS_EDIT_TEXT:
        return "EVENT_TYPE_INPUT_SELECTION_FOCUS_EDIT_TEXT";
      case EVENT_TYPE_INPUT_SELECTION_MOVE_CURSOR_TO_BEGINNING:
        return "EVENT_TYPE_INPUT_SELECTION_MOVE_CURSOR_TO_BEGINNING";
      case EVENT_TYPE_INPUT_SELECTION_MOVE_CURSOR_TO_END:
        return "EVENT_TYPE_INPUT_SELECTION_MOVE_CURSOR_TO_END";
      case EVENT_TYPE_INPUT_SELECTION_MOVE_CURSOR_NO_SELECTION:
        return "EVENT_TYPE_INPUT_SELECTION_MOVE_CURSOR_NO_SELECTION";
      case EVENT_TYPE_INPUT_SELECTION_MOVE_CURSOR_WITH_SELECTION:
        return "EVENT_TYPE_INPUT_SELECTION_MOVE_CURSOR_WITH_SELECTION";
      case EVENT_TYPE_INPUT_SELECTION_MOVE_CURSOR_SELECTION_CLEARED:
        return "EVENT_TYPE_INPUT_SELECTION_MOVE_CURSOR_SELECTION_CLEARED";
      case EVENT_TYPE_INPUT_SELECTION_CUT:
        return "EVENT_TYPE_INPUT_SELECTION_CUT";
      case EVENT_TYPE_INPUT_SELECTION_PASTE:
        return "EVENT_TYPE_INPUT_SELECTION_PASTE";
      case EVENT_TYPE_INPUT_SELECTION_TEXT_TRAVERSAL:
        return "EVENT_TYPE_INPUT_SELECTION_TEXT_TRAVERSAL";
      case EVENT_TYPE_INPUT_SELECTION_SELECT_ALL:
        return "EVENT_TYPE_INPUT_SELECTION_SELECT_ALL";
      case EVENT_TYPE_INPUT_SELECTION_SELECT_ALL_WITH_KEYBOARD:
        return "EVENT_TYPE_INPUT_SELECTION_SELECT_ALL_WITH_KEYBOARD";
      case EVENT_TYPE_INPUT_SELECTION_RESET_SELECTION:
        return "EVENT_TYPE_INPUT_SELECTION_RESET_SELECTION";
      default:
        return AccessibilityEventUtils.typeToString(eventType);
    }
  }

  private static String getFlavorName(@Flavor int flavor) {
    switch (flavor) {
      case FLAVOR_NONE:
        return "FLAVOR_NONE";
      case FLAVOR_ARC:
        return "FLAVOR_ARC";
      case FLAVOR_TV:
        return "FLAVOR_TV";
      case FLAVOR_JASPER:
        return "FLAVOR_JASPER";
      default:
        return "UNKNOWN";
    }
  }
}
