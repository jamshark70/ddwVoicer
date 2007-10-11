
Voicer {		// collect and manage voicer nodes
			// H. James Harkins -- jamshark70@dewdrop-world.net
	
	var	<nodes,
		<voices,		// maximum number of voices
		<target, <>addAction,
		<bus,
		<>stealer = \preferEarly,	// a symbol, method name for node locator
			// must be one of: cycle, strictCycle, random, preferEarly, preferLate
		cycleRout, strictCycRout,	// see cycle and strictCycle

		<globalControls,		// so all nodes can share a bus for a given control
							// an IDict of VoicerGlobalControls
		<proxy,				// my proxy for voicer processes

		<>latency = nil,	// node latency; can be overridden at trigger time
		
		<>clock;

	var	<susPedalNodes, <susPedal = false;

	*new { arg voices = 1, things, args, bus, target, addAction = \addToTail;
			// things can be a single thing or an Array of things
			// args can be an array of pairs [name, value, name, value...] or array of such arrays
		^super.new.init(voices, things, args, bus, target, addAction);
	}
	
	init { arg v, th, ar, b, targ, addAct;
		var args;		// for initializing nodes

		globalControls = IdentityDictionary.new;

		target = targ.asTarget;
		NodeWatcher.newFrom(target.server);	// voicernodes need to watch synths on server

			// using groupBusInfo might seem like a long way around,
			// but it also checks that the target is a Mixer (without isKindOf)
			// thus, the bus argument is overridden ONLY for mixers
		(args = targ.tryPerform(\groupBusInfo)).notNil.if({
			bus = args[1];
		}, {
			bus = b ? Bus.new(\audio, 0, 1, target.server);
		});
		
		addAction = addAct;
		
//		clock = clock ? TempoClock.default;	// otherwise you can only gate from inside a Routine

		voices = (v ? 1).max(1);		// must have at least one node
			// convert initial args to array of arrays
		ar.isNil.if({ ar = [] });
			// if first element is array, you have [[],[]...]
		(ar.at(0).size > 0).if({ args = ar }, { args = [ar] });
			// same thing for patches
		(th.size == 0 or: { th.isString }).if({ th = [th] });

			// create nodes: loop thru things
		nodes = Array.new(voices);	// must add the nodes incrementally
		voices.do({ |i|
			nodes = nodes.add(this.makeNode(th.wrapAt(i), args.wrapAt(i),
					// i < th.size.lcm(args.size) : patches will become superfluous
					// after least common multiple of # of instrs and # of arg sets
				(i < th.size.lcm(args.size)).if({ nil }, // nil=not superfluous, make patch
						// else, wrap around and get defname to reuse
					{ nodes[i % th.size.lcm(args.size)].defname })
			));
		});

		susPedalNodes = IdentitySet.new;
	}
	
	makeNode { arg thing, args, defname;
			// strings/symbols: treat as defname
		case
			{ thing.isString or: { thing.isSymbol } } {
				^SynthVoicerNode.new(thing, args, bus, target, addAction, this, defname);
			}
			
			{ thing.isKindOf(Instr) } {
				^InstrVoicerNode.new(thing, args, bus, target, addAction, this, defname);
			}

				// default branch, error
			{ "Invalid object to use as instrument. Can't build voicer.".die }
	}
	
// SUPPORT METHODS FOR NODE LOCATORS:
	nonplaying {	// returns all nonplaying nodes, (or if none, an array containing earliest node)
		var n;
		n = nodes.select({ arg n; n.isPlaying.not });
		(n.size > 0).if({ ^n }, { ^[ this.earliest ] });
	}
	
	playingNodes {
		^nodes.select(_.isPlaying)
	}
	
	earliest {	// earliest triggered node
		^nodes.copy.sort({ arg a, b; a.lastTrigger < b.lastTrigger }).at(0)
	}
	
	latest {
		^nodes.copy.sort({ arg a, b; a.lastTrigger > b.lastTrigger }).at(0)
	}

		// find earliest active node with this frequency
	firstNodeFreq { arg freq;
		var	nodesTemp;
		nodesTemp = nodes.select({ |n|
			(n.frequency == freq) and: { n.isPlaying and: { n.isReleasing.not } }
		});
			// must not consider pedal-sustaining nodes
		(susPedalNodes.size > 0).if({
			nodesTemp = (IdentitySet(nodesTemp.size).addAll(nodesTemp).removeAll(susPedalNodes))
				.asArray;
		});
		^nodesTemp.sort({ |a, b| a.lastTrigger < b.lastTrigger }).at(0)
	}
	
// NODE LOCATORS:
// to choose one, do yourVoicer.stealer_( a symbol == the method name )
	strictCycle {
			// always returns next item in nodes, whether playing or not
		strictCycRout.isNil.if({
			strictCycRout = Routine.new({
				nodes.do({ arg n; n.yield });
			});
		});
		^strictCycRout.next
	}
	
	cycle {
			// returns next non-playing item in nodes
			// if all nodes playing, returns earliest triggered
		cycleRout.isNil.if({
			cycleRout = Routine.new({
				nodes.do({ arg n;
						// if nonplaying returns a playing node, then all nodes are playing
					(this.nonplaying.at(0).isPlaying).if({
						this.earliest.yield
					}, {
						n.isPlaying.not.if({ n.yield })
					});
				});
			});
		});
		^cycleRout.next
	}
	
	random {
			// returns a random non-playing node
		var n;
		n = this.nonplaying;
			// if 1 or more nodes are not playing, return one of them
		(n.at(0).isPlaying.not).if({ ^n.choose },
			{ ^this.earliest }		// otherwise, give earliest triggered node
		);
	}
	
	preferEarly {
			// find first non-playing node -- THE DEFAULT METHOD
		^this.nonplaying.sort({ arg a, b; a.lastTrigger < b.lastTrigger }).at(0)
	}
	
	preferLate {
			// find last non-playing node
		^this.nonplaying.sort({ arg a, b; a.lastTrigger > b.lastTrigger }).at(0)
	}
	
// PLAYING/RELEASING METHODS:
// trigger plays, release kills a node by frequency, gate starts and schedules the release
		// lat -1 means use value defined in the voicer
	trigger1 { arg freq, gate = 1, args, lat = -1;
		var node;
//["Voicer-trigger1", freq, gate, args, noLatency].asCompileString.postln;
			// freq may be a symbol to produce a rest
		freq.isNumber.if({
			node = this.perform(stealer);
				// args may be [\key, value] or [[\key, value], [\key, value]]
				// in the latter case, trigger1 should take only the first subarray
			node.trigger(freq, gate,
				(args.size > 0 and: { args[0].respondsTo(\wrapAt) }).if(
					{ args[0] }, { args }), (lat ? 0).isNegative.if({ latency }, { lat }));
			^node	// give node back to user
		}, {
			^nil
		});
	}
	
		// trigger one or many
	trigger { arg freq, gate = 1, args, lat = -1;
		var bundle, node, nodecoll;
		(lat ? 0).isNegative.if({ lat = latency });
		(freq.size > 0).if({ 
				// if many freqs, convert args to array of arrays if it's not already
			args.isNil.if({ args = [] });
			args.at(0).respondsTo(\wrapAt).not.if({ args = [args] });
				// same for gates - otherwise, an array for gates will cause stuck nodes
			gate.isNil.if({ gate = [1] });
			gate.respondsTo(\wrapAt).not.if({ gate = [gate] });
				// for each freq, get node and play it
			^freq.collect({ arg f, i;
//node = this.perform(stealer);
//["Voicer-trigger", node.shouldSteal, nodes.select({ |n| n.shouldSteal }).size].postln;
				f.isNumber.if({
					this.perform(stealer).trigger(f, gate.wrapAt(i), args.wrapAt(i), lat);
				});
			});
		}, {
			^this.trigger1(freq, gate, args, lat);
		});
	}		
	
	gate1 { arg freq, dur, gate = 1, args, lat = -1;
			// play & schedule release for 1 note
		var node, synth;
//["Voicer-gate1", freq, dur, gate, args].asCompileString.postln;
		(lat ? 0).isNegative.if({ lat = latency });
		node = this.trigger1(freq, gate, args, lat);
		synth = node.synth;
		(clock ? thisThread.clock).sched(dur, { 
//			(node.frequency == freq).if({
				node.release(0, lat, freq)
//			}, {
//// temp fix for zombie problem with fast notes and no stealing
//				synth.isPlaying.if({ synth.server.sendBundle(lat, synth.setMsg(\gate, 0)) });
//			});
		});
		^node
	}
	
		// gate one or many
	gate { arg freq, dur, gate = 1, args, lat = -1;
		var nodecoll;
		(lat ? 0).isNegative.if({ lat = latency });
		(freq.size > 0).if({
			nodecoll = this.trigger(freq, gate ? 1, args, lat);  // play them
				// if single dur, convert to array
			(dur.size > 0).not.if({ dur = [dur] });
			nodecoll.do({		// schedule releases
				arg node, i;
				(clock ? thisThread.clock).sched(dur.wrapAt(i), {
					node.release(0, lat, freq.wrapAt(i))
				});
			});
			^nodecoll
		}, {
			^this.gate1(freq, dur, gate, args, lat)
		});
	}
	
	release1 { arg freq, lat = -1;
		var node;
//(freq.neg.asString++",").post;
		(node = this.firstNodeFreq(freq)).notNil.if({
			susPedal.if({
				susPedalNodes.add(node);
			}, {
				node.release(0, (lat ? 0).isNegative.if({ latency }, { lat }));
			});
		});
	}
	
	release { arg freq, lat = -1;
		var node, nodecoll;
		(freq.size > 0).not.if({
			this.release1(freq, lat)
		}, {
			freq.collect({ arg f; this.release1(f, lat) });
		});
	}
	
	releaseAll {
		nodes.do({ arg n; n.release });
		susPedalNodes = IdentitySet.new;
	}
	
// suspednodes?
	releaseNow1 { arg freq, sec;
		^this.firstNodeFreq(freq).releaseNow(sec);
	}
	
	releaseNow { arg freq, sec;
		var node;
		(freq.size > 0).not.if({
			^this.releaseNow1(freq, sec)
		}, {
			^freq.collect({ arg f; this.release1(f, sec) });
		});
	}
	
// CONVENIENCE: Apply methods to many nodes
//	latency_ { arg l;
//		nodes.do({ arg n; n.latency = l; });
//	}
//
	set { arg args, lat;
		var bus, ar;	// bus holder used in loops, argument sub-collection
		
		(lat ? 0).isNegative.if({ lat = latency });

		args = args.clump(2);	// group in pairs

			// do global-mapped controls
			// if globalControls dict returns non-nil for this name, then it's mapped
		ar = args.select({ arg a; globalControls.at(a.at(0).asSymbol).notNil });
		
			// set the buses to the associated values
		ar.do({ arg a; globalControls.at(a.at(0).asSymbol).set(a.at(1), true, lat); });

			// now do non-global controls
		ar = args.reject({ arg a; globalControls.at(a.at(0).asSymbol).notNil }).flatten(1);

			// must do this way b/c nodes may be of different types
			// node's responsibility to check if it's loaded
		nodes.do({ arg n; n.set(ar, lat); });
	}
	
		// apply to initArgs within nodes
		// does not affect currently playing nodes, only new ones
	setArgDefaults { arg args;
		nodes.do({ |n| n.setArgDefaults(args); });
	}
	
	target_ { |targ|
		var	groupbus;
			// check for mixerchannel
		(groupbus = targ.tryPerform(\groupBusInfo)).notNil.if({
			#target, bus = groupbus;
		}, {
			target = targ.asTarget;	// if not MC, then use current bus
		});
			// must propagate to all the nodes
		nodes.do({ |n|
			n.target = target;
			n.bus = bus;
		})
	}
	
	sustainPedal { |sustain|
		susPedal = sustain ?? { susPedal.not };
			// do I need to fix the array here?
		susPedal.not.if({
			susPedalNodes.do({ |n| n.release(0) });  // is that right?
			susPedalNodes = IdentitySet.new;
		});
	}

	mapGlobal { arg name, bus, value, spec, allowGUI = true; // maps name to a kr bus in every node
		var	gc;
		globalControls.put(name, gc = VoicerGlobalControl.new(name, bus, this,
			value ? 0, spec, allowGUI));
		^gc		// so user can reference this gc directly
	}
	
	unmapGlobal { arg name;
		var gc;
		name = name.asSymbol;
		gc = globalControls.at(name);
		gc.notNil.if({		// make sure there's something to remove
			globalControls.removeAt(name);
			gc.free;
		});
	}
	
	maxControlNum {	// for indexing in VoicerGlobalControl
		^globalControls.collect({ |gc, key| gc.voicerIndex }).maxItem ? 0
	}
	
	globalControlsByCreation {
		^globalControls.values.select({ |gc| gc.allowGUI })
			.asArray.sort({ |a,b| a.voicerIndex < b.voicerIndex })
	}
	
	proxify {
		proxy.isNil.if({
			proxy = VoicerProxy.new(this);
		});
		^proxy
	}
	
	addProcess { arg states, type;
		this.proxify;
		^proxy.addProcess(states, type);
	}
	
	removeProcess { arg p;
		^proxy.tryPerform(\removeProcess, p)
	}
	
	removeProcessAt { arg i;
		^proxy.tryPerform(\removeProcessAt, i)
	}
	
	processes { ^proxy.tryPerform(\processes) }

// BOOKKEEPING:
	free {
			// activates onClose which frees the gui
		nodes.do(_.dtor);	// clean up stuff
		proxy.notNil.if({ proxy.modelWasFreed; });	// deactivate proxy
		globalControls.do({ arg gc; gc.free });
		globalControls = IdentityDictionary.new;
		voices = nil;
		MIDIPort.update;	// clears VoicerMIDISocket associated with me
						// if socket is pointing to proxy, the socket will stay put
	}
	
	active { ^voices.notNil }
	
	run { arg bool = true;
		var mixer;
		(mixer = this.asMixer).notNil.if({
			mixer.run(bool);
		}, {
			target.run(bool).isRunning_(bool);
		});
	}

	isRunning {
		var mixer;
		^(mixer = this.asMixer).notNil.if({
			mixer.isRunning;
		}, {
			target.isRunning ? true;
		});
	}
	
	asMixer { ^bus.asMixer }
	
	panic {		// free all nodes
		nodes.do({ arg n; n.releaseNow });
	}
	
	cleanup {		// free non-playing nodes; kind of superfluous now
		this.nonplaying.do({ arg n; n.free });
	}

	steal_ { |bool = true|
		nodes.do(_.steal = bool);
	}
	
		// trace all playing nodes
		// no need to check here b/c VoicerNode tests isPlaying before issuing n_trace
	trace {
		nodes.do({ |node| node.trace });
	}
	
// GUI support
	guiClass { ^VoicerGUI }
	
	asString { ^("Voicer : " ++ nodes.at(0).displayName) }
	
	editor { proxy.isNil.if({ ^nil }, { ^proxy.editor }) }
	
	draggedIntoVoicerGUI { arg dest;		// drag a voicer into a gui switches the gui to this vcr
		var oldProxy;
//"Voicer-draggedIntoVoicerGUI".postln; this.postln; dest.dump; "\n\n".postln;
		oldProxy = proxy;		// must clear from old gui if there was one
//"Voicer-draggedIntoVoicerGUI: setting new proxy".postln;
		dest.model.voicer_(this);	// set new gui's proxy to this voicer
//"Voicer-draggedIntoVoicerGUI: setting old proxy".postln;
		oldProxy.notNil.if({ oldProxy.voicer_(nil) }
//, { "\n\n\nDID NOT SET OLD PROXY'S VOICER TO NIL".postln; this.dumpBackTrace; "\n\n".postln; }
);	// clear old proxy
//		dest.updateStatus;	// handled in voicer_?
	}
	
	proxy_ { arg pr;	// set my proxy and fix my gc's proxies
//this.insp; pr.insp;
//"Voicer-proxy_".postln; this.postln; pr.dump; "\n\n".postln;
//this.dumpBackTrace;
		pr.isNil.if({ proxy.clearControlProxies });
		proxy = pr;
		proxy.notNil.if({ proxy.switchControlProxies });
	}
	
// chucklib support
	bindClassName { ^Voicer }

//	asVoicer { ^this }

// drop in some events to use with voicers
	*initClass {
		Class.initClassTree(Event);
		Event.parentEvents.put(\voicerMIDI, (args: [],
			
				// maybe you want to use non-equal-temperament. write it here
			midiNoteToFreq: #{ |notenum|
				notenum.midicps
			},
			
			prepNote: #{
				var i;
				~freq = ~freq ?? { ~note.freq };
				(~midi ? true).if({ ~freq = ~midiNoteToFreq.value(~freq) });
				~delta = ~delta ? ~note.dur;
				~length = ~length ? ~note.length;
				~args = ~args ? ~note.args;
				~gate = ~gate ?? {
						// identify the \gate, xxx pair in the args array
						// 2nd removeAt should return the value *wink*
					(i = ~args.detectIndex({ |item| item == \gate })).notNil
						.if({ ~args.removeAt(i); ~args.removeAt(i); }, { 0.5 });
				};
				(~argKeys.size > 0).if({
					~args = ~args ++
						~argKeys.collect({ |a| [a, a.envirGet].flop }).flop
							.collect({ |a| a.flatten(1) });
				});
			},
			
			play: #{
				~prepNote.value;
				~finish.value;	// user-definable
				~voicer.notNil.if({
					~length.isNil.if({		// midi input... note will be released later
						~voicer.trigger(~freq, ~gate, ~args, ~latency);
					}, {
						~voicer.gate(~freq, ~length, ~gate, ~args, ~latency);
					});
				});
			}
		));

		Event.default[\eventTypes].put(\voicerNote, #{|server|
			var freqs, lag, dur, strum, sustain, desc, msgFunc, i;
			
			freqs = ~freq = ~freq.value + ~detune;
							
			if (freqs.isSymbol.not) {
				~amp = ~amp.value;
				lag = ~lag + server.latency;
				strum = ~strum;
				sustain = ~sustain = ~sustain.value;
				~gate = ~gate ?? {
						// identify the \gate, xxx pair in the args array
						// 2nd removeAt should return the value *wink*
					(i = ~args.detectIndex({ |item| item == \gate })).notNil
						.if({ ~args.removeAt(i); ~args.removeAt(i); }, { 0.5 });
				};
				~args = [[\freq, freqs], [\sustain, sustain], [\gate, ~gate]] ++ ~voicerArgs;
				(~argKeys.size > 0).if({
					~args = ~args ++
						~argKeys.collect({ |a| [a, a.envirGet] });
				});
				~args = ~args.collect(_.flop).flop.collect(_.flatten(1));
				~args.do {|msgArgs, i|
					var latency;
					
					latency = i * strum + lag;
					
					~voicer.gate1(msgArgs[1], msgArgs[3], msgArgs[5], 
						msgArgs[6..max(6, msgArgs.size-1)], latency);
				}
			};
		});
	}

}

MonoPortaVoicer : Voicer {

	var	<>portaTime = 0,	// portamento time
		<lastFreqs;		// last triggered frequencies (for portamento)
		
	init { arg v, th, ar, b, targ, addAct, preAlloc;
		var args;		// for initializing nodes

		globalControls = IdentityDictionary.new;
		lastFreqs = List.new;

		target = targ.asTarget;
		NodeWatcher.newFrom(target.server);	// voicernodes need to watch synths on server

		(args = targ.tryPerform(\groupBusInfo)).notNil.if({
			bus = args[1];
		}, {
			bus = b ? Bus.new(\audio, 0, 1, target.server);
		});
		
		addAction = addAct;

// defaults to thisThread.clock now		
//		clock = clock ? SystemClock;	// otherwise you can only gate from inside a Routine
		
		voices = 1;		// may have only one node for a mono voicer
			// convert initial args to array of arrays
		ar.isNil.if({ ar = [] });
			// if first element is array, you have [[],[]...]
		(ar.at(0).size > 0).if({ args = ar }, { args = [ar] });
			// create nodes: loop thru things
		(th.size > 0 and: { th.isString.not }).if({
			nodes = Array.fill(voices, { arg i;
				this.makeNode(th.wrapAt(i), args.wrapAt(i), preAlloc, i);
			})
		}, {
			nodes = Array.fill(voices, {arg i;
				this.makeNode(th, args.wrapAt(i), preAlloc);
			})
		});
	}
	
	makeNode { arg thing, args, preAlloc;
			// strings/symbols: treat as defname
		case
			{ thing.isString or: { thing.isSymbol } } {
				^MonoPortaSynthVoicerNode.new(thing, args, bus, target, addAction, this);
			}
			
			{ thing.isKindOf(Instr) } {
				^MonoPortaInstrVoicerNode.new(thing, args, bus, target, addAction, this);
			}

				// default branch, error
			{ "Invalid object to use as instrument. Can't build voicer.".die }
	}
	
	release1 { arg freq, lat = -1;
//["MonoPortaVoicer-release1", freq, noLatency, lastFreqs].asCompileString.postln;
		(lat ? 0).isNegative.if({ lat = latency });
		lastFreqs.remove(freq);
		(lastFreqs.size > 0).if({
			nodes.at(0).set([\freq, lastFreqs.last], lat);
			nodes.at(0).frequency = lastFreqs.last;
			^nodes.at(0)
		}, {
			^this.firstNodeFreq(freq).release(0, lat)
		});
	}
	
	gate1 { arg freq, dur, gate = 1, args, lat = -1;
			// play & schedule release for 1 note
		var node;
		(lat ? 0).isNegative.if({ lat = latency });
		node = this.trigger1(freq, gate, args, lat);
		(clock ? thisThread.clock).sched(dur, { this.release(freq, lat) });
		^node
	}
	
		// gate one or many
	gate { arg freq, dur, gate = 1, args, lat = -1;
		var node, nodecoll;
//["MonoPortaVoicer-gate", freq, dur, gate, args].postln;
		(lat ? 0).isNegative.if({ lat = latency });
		(freq.size > 0).if({
			nodecoll = this.trigger(freq, gate ? 1, args, lat);  // play them
				// if single dur, convert to array
			(clock ? thisThread.clock).sched(dur, { this.release(freq, lat) });
			^nodecoll
		}, {
			^this.gate1(freq, dur, gate, args, lat)
		});
	}
	
	panic {	// panic button needs to clear lastFreqs list in additiion to other activities
		super.panic;
		lastFreqs = List.new;
	}
	
}


// this may not be required since node stealing has been fixed

VoicerNoGate : Voicer {	// just like Voicer, except synthdefs should use fixed-length envelopes

		// since env is fixed-length, no need to schedule releases
		// but, we do need to set the release flag in the node so
		// the node can be reused without stealing
	gate1 { arg freq, dur, gate = 1, args, lat = -1;
			// play & schedule release for 1 note
		var node;
		node = this.trigger1(freq, gate, args, (lat ? 0).isNegative.if({ latency }, { lat }));
		node.isPlaying = false;
		^node
	}
	
		// gate one or many
	gate { arg freq, dur, gate = 1, args, lat = -1;
		var node, nodecoll;
//[freq, dur, gate, args].asCompileString.postln;
		(lat ? 0).isNegative.if({ lat = latency });
		(freq.size > 0).if({
			nodecoll = this.trigger(freq, gate ? 1, args, lat);  // play them
			^nodecoll.do({ |node| node.isPlaying = false });
		}, {
			^this.gate1(freq, dur, gate, args, lat)
		});
	}		
	
}
