const remoteVideo = document.getElementById('remoteVideo');
const startButton = document.getElementById('startButton');
const stopButton = document.getElementById('stopButton');
const statusDiv = document.getElementById('status');

let pc; // RTCPeerConnection
let signalingSocket; // WebSocket cho signaling

const SIGNALING_SERVER_URL = 'ws://localhost:8080/ws'; // THAY ĐỔI URL NÀY!

// Cấu hình STUN server (cần thiết cho NAT traversal)
const iceConfiguration = {
    iceServers: [
        { urls: 'stun:stun.l.google.com:19302' },
        // Thêm TURN server nếu cần cho các mạng phức tạp hơn
        // {
        //   urls: 'turn:your.turn.server:3478',
        //   username: 'user',
        //   credential: 'password'
        // }
    ]
};

function updateStatus(message) {
    console.log(message);
    statusDiv.textContent = `Status: ${message}`;
}

function startSession() {
     if (pc) {
        updateStatus("Session already active. Please disconnect first.");
        return;
    }
    updateStatus("Connecting to signaling server...");
    startButton.disabled = true;

    signalingSocket = new WebSocket(SIGNALING_SERVER_URL);

    signalingSocket.onopen = async () => { // async để dùng await cho createOffer
        updateStatus("Connected to signaling server. Creating offer...");
        createPeerConnection(); // Tạo PC trước
        try {
            // Thêm transceiver để chỉ định chúng ta muốn nhận video
            // 'recvonly' nghĩa là chúng ta chỉ nhận, không gửi video.
            pc.addTransceiver('video', { direction: 'recvonly' });
            // Nếu bạn cũng muốn nhận audio từ Android (ví dụ: mic), thêm transceiver cho audio:
            // pc.addTransceiver('audio', { direction: 'recvonly' });

            const offer = await pc.createOffer();
            await pc.setLocalDescription(offer);
            updateStatus("Offer created. Sending offer...");
            signalingSocket.send(JSON.stringify(pc.localDescription)); // Gửi offer
        } catch (error) {
            handleError("Error creating or sending offer: " + error);
        }
    };

    signalingSocket.onmessage = async (event) => {
        const message = JSON.parse(event.data);
        console.log("Received from signaling:", message);

        if (message.type === 'answer') { // Bây giờ chúng ta mong đợi 'answer'
            if (pc && !pc.currentRemoteDescription) { // Chỉ set remote desc nếu chưa có
                updateStatus("Answer received. Setting remote description...");
                try {
                    await pc.setRemoteDescription(new RTCSessionDescription(message));
                } catch (error) {
                    handleError("Error setting remote description (answer): " + error);
                }
            }
        } else if (message.type === 'candidate') {
            if (pc && pc.remoteDescription) { // Chỉ add candidate sau khi đã set remote description
                try {
                    const candidate = new RTCIceCandidate(message.candidate);
                    await pc.addIceCandidate(candidate);
                    updateStatus("ICE candidate added.");
                } catch (error) {
                    handleError("Error adding ICE candidate: " + error);
                }
            } else {
                console.warn("PeerConnection not ready for candidate or no remote description.");
                // Cân nhắc việc xếp hàng candidate nếu chúng đến quá sớm
            }
        } else if (message.type === 'disconnect' || message.type === 'bye') {
            updateStatus("Remote peer disconnected.");
            stopSession();
        } else {
            console.warn("Unknown message type from signaling:", message.type);
        }
    };

    signalingSocket.onerror = (error) => {
        handleError("Signaling WebSocket error: " + error);
        startButton.disabled = false;
    };

    signalingSocket.onclose = () => {
        updateStatus("Disconnected from signaling server.");
        if (pc) {
            stopSession(); // Dọn dẹp nếu socket đóng đột ngột
        }
        startButton.disabled = false;
        stopButton.classList.add('hidden');
    };
}

function createPeerConnection() {
    updateStatus("Creating PeerConnection...");
    pc = new RTCPeerConnection(iceConfiguration);

    pc.onicecandidate = (event) => {
        if (event.candidate) {
            updateStatus("Generated ICE candidate. Sending...");
            // Gửi candidate đến peer kia thông qua signaling server
            // Message cần có cấu trúc mà Android app (libdatachannel) hiểu được
            // Ví dụ: { type: 'candidate', candidate: event.candidate.toJSON() }
            // libdatachannel có thể mong đợi mid, sdpMLineIndex, sdp
            const candidateMessage = {
                type: 'candidate',
                candidate: {
                    sdpMid: event.candidate.sdpMid,
                    sdpMLineIndex: event.candidate.sdpMLineIndex,
                    candidate: event.candidate.candidate
                }
            };
            signalingSocket.send(JSON.stringify(candidateMessage));
        }
    };

    pc.oniceconnectionstatechange = () => {
        updateStatus(`ICE connection state: ${pc.iceConnectionState}`);
        if (pc.iceConnectionState === 'connected' || pc.iceConnectionState === 'completed') {
            stopButton.classList.remove('hidden');
            startButton.disabled = true;
        } else if (pc.iceConnectionState === 'failed' || pc.iceConnectionState === 'disconnected' || pc.iceConnectionState === 'closed') {
            stopSession();
        }
    };

    pc.ontrack = (event) => {
        updateStatus("Video track received!");
        if (event.streams && event.streams[0]) {
            remoteVideo.srcObject = event.streams[0];
        } else {
            // Fallback cho trình duyệt cũ hơn
            let inboundStream = new MediaStream();
            inboundStream.addTrack(event.track);
            remoteVideo.srcObject = inboundStream;
        }
        remoteVideo.play().catch(e => console.error("Autoplay failed:", e));
    };

    // Thêm transceivers nếu bạn là người tạo offer (trong trường hợp này, web là người nhận offer)
    // Nếu web là người tạo offer (ít phổ biến hơn trong kịch bản này):
    // pc.addTransceiver('video', { direction: 'recvonly' });
    // pc.addTransceiver('audio', { direction: 'recvonly' }); // Nếu có audio
}

function stopSession() {
    updateStatus("Disconnecting...");
    if (pc) {
        pc.close();
        pc = null;
    }
    if (signalingSocket && signalingSocket.readyState === WebSocket.OPEN) {
        // Gửi message "bye" đến peer kia (tùy chọn, để dọn dẹp phía bên kia)
        signalingSocket.send(JSON.stringify({ type: 'bye' }));
        signalingSocket.close();
    }
    signalingSocket = null;
    remoteVideo.srcObject = null;
    startButton.disabled = false;
    stopButton.classList.add('hidden');
    updateStatus("Disconnected.");
}

function handleError(errorMessage) {
    console.error(errorMessage);
    updateStatus(`Error: ${errorMessage}`);
    // Có thể thêm logic reset hoặc thông báo cho người dùng
    if (pc) stopSession(); // Dừng phiên nếu có lỗi nghiêm trọng
    startButton.disabled = false;
}

// Khởi tạo
updateStatus("Ready. Click 'Connect to Stream' to start.");