package com.nexa.api.salescommitment.presentation.salesorder.request;

import jakarta.validation.constraints.Size;

public record ConversionNoteRequest(@Size(max = 2000) String note) { }
