
+ Ref {
	draggedIntoVoicerGUI { |dest| value.draggedIntoVoicerGUI(dest) }
}

+ Object {
	draggedIntoVoicerGCGUI { |gui|
		if(this.respondsTo(\asSpec)) {
			gui.model.spec = this.asSpec;
		}
	}

	// asTestUGenInput { ^this.asUGenInput }
}

	// needed because Cocoa GUI no longer interprets strings for you
+ String {
	draggedIntoVoicerGUI { |gui|
		^this.interpret.draggedIntoVoicerGUI(gui)
	}

	draggedIntoVoicerGCGUI { |gui|
		^this.interpret.draggedIntoVoicerGCGUI(gui)
	}
}

+ SequenceableCollection {
	findPairKeys { |keyList, prototype|
		var remain = keyList.size;
		var out = Array.newClear(remain);
		var set = IdentityDictionary.new;
		if(prototype.isSequenceableCollection) {
			out.overWrite(prototype, 0);
		};
		keyList.do { |key, i| set.put(key, i) };
		this.pairsDo { |a, b, i|
			var index = set[a];
			if(index.notNil) {
				out[index] = b;
				set.removeAt(a);
				remain = remain - 1;
				if(remain == 0) { ^out };
			};
		};
		^out
	}
}
