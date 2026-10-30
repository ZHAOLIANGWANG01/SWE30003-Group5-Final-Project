(() => {
  const isField = (field) => field instanceof HTMLInputElement
    || field instanceof HTMLSelectElement || field instanceof HTMLTextAreaElement;

  const messageFor = (field) => {
    const validity = field.validity;
    if (validity.valueMissing) {
      return field instanceof HTMLSelectElement
        ? "Please select an option." : "Please fill out this field.";
    }
    if (validity.typeMismatch) return "Please enter a value in the required format.";
    if (validity.rangeUnderflow) return `Please enter a value of at least ${field.min}.`;
    if (validity.rangeOverflow) return `Please enter a value no greater than ${field.max}.`;
    if (validity.tooLong) return `Please use no more than ${field.maxLength} characters.`;
    if (validity.tooShort) return `Please use at least ${field.minLength} characters.`;
    if (validity.stepMismatch) return "Please enter a value using the required increment.";
    if (validity.patternMismatch) return "Please match the requested format.";
    return "Please enter a valid value.";
  };

  document.addEventListener("invalid", (event) => {
    const field = event.target;
    if (!isField(field)) return;
    field.setCustomValidity("");
    if (!field.validity.valid) field.setCustomValidity(messageFor(field));
  }, true);

  const clearMessage = (event) => {
    if (isField(event.target)) event.target.setCustomValidity("");
  };
  document.addEventListener("input", clearMessage);
  document.addEventListener("change", clearMessage);
})();
