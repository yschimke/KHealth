import SwiftUI
import sampleShared

struct ContentView: View {
    var body: some View {
        VStack(spacing: 24) {
            Text("Please open your logs to check outputs of all button clicks")
                .multilineTextAlignment(.center)
            
            Button("Check All Permissions") {
                KHealthSample_appleKt.sampleCheckAllPerms()
            }
            
            Button("Request All Permissions") {
                KHealthSample_appleKt.sampleRequestAllPerms()
            }
            
            Button("Write Data") {
                KHealthSample_appleKt.sampleWriteData()
            }
            
            Button("Read Data") {
                KHealthSample_appleKt.sampleReadData()
            }
        }
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}
