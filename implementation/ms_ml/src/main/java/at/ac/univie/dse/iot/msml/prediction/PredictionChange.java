package at.ac.univie.dse.iot.msml.prediction;

import at.ac.univie.dse.iot.common.model.DataType;

/** The predicted bin of a data type changed; {@code previous} is null for the first prediction. */
public record PredictionChange(DataType dataType, Prediction previous, Prediction current) {
}
