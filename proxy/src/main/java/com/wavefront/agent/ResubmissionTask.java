package com.wavefront.agent;

import com.squareup.tape.Task;
import com.wavefront.api.WavefrontAPI;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;

/**
 * A task for resubmission.
 *
 * @author Clement Pang (clement@wavefront.com).
 */
public abstract class ResubmissionTask<T extends ResubmissionTask<T>> implements Task, Serializable {

  /**
   * To be injected. Should be null when serialized.
   */
  protected transient WavefrontAPI service = null;

  /**
   * To be injected. Should be null when serialized.
   */
  protected transient UUID currentAgentId = null;

  /**
   * To be injected. Should be null when serialized.
   */
  protected transient String token = null;

  /**
   * @return The relative size of the task
   */
  public abstract int size();

  public abstract List<T> splitTask();
}
